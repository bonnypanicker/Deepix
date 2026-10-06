package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File

/**
 * App-managed Recycle Bin. Deleted photos are copied into app-private storage ([binDir]) and the
 * original file is removed directly (requires All-files access — see [StoragePermissions]). Each
 * entry remembers where it came from so it can be restored to the same location, and anything older
 * than [RETENTION_MS] (30 days) is purged on app start.
 *
 * Storage is app-private (`filesDir/bin`), so binned photos are invisible to other galleries and are
 * cleared if the app is uninstalled — a deliberate privacy/simplicity trade-off.
 *
 * The bookkeeping lives in [BinLedger] — one sidecar record per photo, written before the copy starts
 * and re-read after any interruption. A move-in therefore has three observable states (announced,
 * copied, committed) and a process kill in any of them leaves the photo either in its original folder
 * or whole inside the bin. The copy is verified against the source size *before* the original is
 * deleted, and a delete that reports failure rolls the entry back.
 *
 * Every mutation holds [lock]: the bin is written from the UI thread's undo path, the delete
 * coordinator, and the app-start purge.
 */
object BinManager {

    private const val TAG = "BinManager"
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    private val lock = Any()

    data class BinResult(
        val binned: Int,
        val failed: Int,
        val binnedIds: List<String> = emptyList(),
        val binnedUris: List<Uri> = emptyList()
    )

    private fun binDir(context: Context): File =
        File(context.filesDir, "bin").apply { mkdirs() }

    /** The photo's bytes inside the bin. */
    fun storedFile(context: Context, entry: BinEntry): File = BinLedger.dataFile(binDir(context), entry.id)

    // ---- Move-in (delete) ----

    /**
     * Copies each uri into the bin and deletes the original file directly. Requires All-files
     * access; without it, nothing is binned (caller should have checked and prompted).
     */
    fun moveToBin(context: Context, uris: List<Uri>): BinResult = synchronized(lock) {
        val root = binDir(context)
        if (!StoragePermissions.hasAllFilesAccess(context)) return@synchronized BinResult(0, uris.size)
        reconcileLocked(root)
        var binned = 0
        var failed = 0
        var refusedForSpace = 0
        val binnedIds = mutableListOf<String>()
        val binnedUris = mutableListOf<Uri>()
        for (uri in uris) {
            try {
                val path = MediaFileOps.resolvePath(context, uri)
                val display = queryName(context, uri) ?: path?.let { File(it).name } ?: "photo_${System.nanoTime()}"
                val mime = context.contentResolver.type(uri)
                val expectedSize = querySize(context, uri) ?: path?.let { File(it).length() } ?: 0L

                if (!BinLedger.hasRoomFor(usableBytes(root), expectedSize)) {
                    refusedForSpace++
                    continue
                }

                val id = "${System.nanoTime()}_${sanitize(display)}"
                val data = BinLedger.dataFile(root, id)
                val announced = BinEntry(
                    id = id,
                    fileName = display,
                    originalPath = path,
                    mimeType = mime,
                    deletedAt = System.currentTimeMillis(),
                    sizeBytes = expectedSize,
                    state = BinState.Pending
                )
                // Announce the intent before touching a byte: a kill after this point is a kill the
                // ledger can read, not one that strands the copy as an anonymous file.
                if (!BinLedger.write(root, announced)) {
                    data.delete()
                    failed++
                    continue
                }

                if (!MediaFileOps.copyToFile(context, uri, data) ||
                    !BinLedger.copyComplete(expectedSize, data.length())
                ) {
                    // The original is untouched, so the partial copy is worthless here.
                    BinLedger.discard(root, id)
                    failed++
                    continue
                }

                val committed = announced.copy(state = BinState.Committed, sizeBytes = data.length())
                if (!BinLedger.write(root, committed)) {
                    BinLedger.discard(root, id)
                    failed++
                    continue
                }

                if (!MediaFileOps.deleteFileDirect(context, uri)) {
                    // The photo is still in the gallery; the bin copy was only insurance.
                    BinLedger.discard(root, id)
                    failed++
                    continue
                }
                binned++
                binnedIds.add(id)
                binnedUris.add(uri)
            } catch (e: Exception) {
                // Leave whatever the ledger recorded: reconcile decides between the original and the copy.
                Log.w(TAG, "Failed to bin $uri", e)
                failed++
            }
        }
        if (refusedForSpace > 0) {
            Log.w(TAG, "Refused $refusedForSpace move(s): not enough free space in $root")
        }
        BinResult(binned, failed + refusedForSpace, binnedIds, binnedUris)
    }

    /** Restores the given bin entries (by id) back to their original folders. Returns restored count. */
    fun restoreByIds(context: Context, ids: Collection<String>): Int = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized 0
        val wanted = ids.toSet()
        var restored = 0
        BinLedger.list(binDir(context)).filter { it.id in wanted }.forEach { entry ->
            if (restoreLocked(context, entry)) restored++
        }
        restored
    }

    // ---- Restore / delete-forever ----

    /** Writes a binned photo back to its original folder and removes it from the bin. */
    fun restore(context: Context, entry: BinEntry): Boolean = synchronized(lock) {
        restoreLocked(context, entry)
    }

    private fun restoreLocked(context: Context, entry: BinEntry): Boolean {
        val root = binDir(context)
        val src = BinLedger.dataFile(root, entry.id)
        if (!src.exists()) {
            BinLedger.discard(root, entry.id)
            return false
        }
        val targetPath = entry.originalPath
        val restored = if (targetPath != null && StoragePermissions.hasAllFilesAccess(context)) {
            runCatching {
                val dest = uniqueFile(File(targetPath))
                if (!BinLedger.copyDataOut(root, entry, dest)) return@runCatching false
                // Verified before the record goes: an unreadable restore must leave the bin entry alone.
                if (dest.length() != src.length()) {
                    dest.delete()
                    return@runCatching false
                }
                MediaFileOps.rescan(context, dest.absolutePath)
                true
            }.getOrDefault(false)
        } else {
            // Fallback: insert via MediaStore into Pictures/Deepix.
            insertViaMediaStore(context, src, entry)
        }
        if (restored) BinLedger.discard(root, entry.id)
        return restored
    }

    /** @return whether both the bytes and the record are gone. */
    fun deleteForever(context: Context, entry: BinEntry): Boolean = synchronized(lock) {
        BinLedger.discard(binDir(context), entry.id)
    }

    fun emptyBin(context: Context) = synchronized(lock) {
        val root = binDir(context)
        BinLedger.list(root).forEach { entry -> BinLedger.discard(root, entry.id) }
        // Quarantined remnants (bytes whose original is gone) are deliberately left: they are the only
        // copy of something, and they are not listed as photos.
    }

    /** Deletes bin entries older than the retention window. Call on app start. */
    fun purgeExpired(context: Context) = synchronized(lock) {
        val root = binDir(context)
        val now = System.currentTimeMillis()
        BinLedger.list(root)
            .filter { now - it.deletedAt > RETENTION_MS }
            .forEach { entry -> BinLedger.discard(root, entry.id) }
    }

    /**
     * Resolves whatever an interrupted delete left behind, and picks up loose files. Runs on app start
     * and before each move-in.
     */
    fun reconcile(context: Context): BinLedger.Reconciled = synchronized(lock) {
        adoptLegacyIndex(binDir(context))
        reconcileLocked(binDir(context))
    }

    private fun reconcileLocked(root: File): BinLedger.Reconciled =
        BinLedger.reconcile(root, BinLedger::originalStillThere)

    fun count(context: Context): Int = list(context).size

    /** Newest-first list for the Bin screen. */
    fun list(context: Context): List<BinEntry> = synchronized(lock) {
        BinLedger.list(binDir(context))
    }

    /** Content uri for displaying a binned file (via FileProvider). */
    fun contentUri(context: Context, entry: BinEntry): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.binprovider",
            storedFile(context, entry)
        )

    // ---- Persistence ----

    /**
     * Reads the old aggregate `bin_index.json`, turning each row whose bytes are still present into a
     * sidecar, then removes it. A JSON file that can't be parsed is renamed aside and its leftover
     * photos are adopted as [BinState.Recovered] by the reconcile that follows — an unreadable index
     * never reads as an empty bin.
     */
    private fun adoptLegacyIndex(root: File) {
        val legacy = File(root, BinLedger.LegacyIndexName)
        if (!legacy.isFile) return
        val entries = runCatching {
            val arr = JSONArray(legacy.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BinEntry(
                    id = o.getString("id"),
                    fileName = o.optString("fileName"),
                    originalPath = o.optString("originalPath").ifBlank { null },
                    mimeType = o.optString("mimeType").ifBlank { null },
                    deletedAt = o.optLong("deletedAt"),
                    sizeBytes = o.optLong("sizeBytes"),
                    state = BinState.Committed
                )
            }
        }
        if (entries.isFailure) {
            legacy.renameTo(File(root, BinLedger.LegacyIndexName + ".corrupt"))
            Log.w(TAG, "Bin index unreadable; its photos will be adopted as recovered", entries.exceptionOrNull())
            return
        }
        var adopted = 0
        for (entry in entries.getOrDefault(emptyList())) {
            val data = BinLedger.claimData(root, entry.id) ?: continue
            val record = entry.copy(sizeBytes = if (entry.sizeBytes > 0L) entry.sizeBytes else data.length())
            if (BinLedger.write(root, record)) adopted++
        }
        if (legacy.delete()) Log.i(TAG, "Migrated $adopted bin entries to sidecars")
    }

    /** Free bytes the bin may use, or [Long.MAX_VALUE] when the volume can't be asked. */
    private fun usableBytes(root: File): Long = runCatching {
        val stats = StatFs(root.absolutePath)
        stats.availableBlocksLong * stats.blockSizeLong
    }.getOrDefault(Long.MAX_VALUE)

    // ---- Helpers ----

    private fun insertViaMediaStore(context: Context, src: File, entry: BinEntry): Boolean {
        return runCatching {
            // Route by actual type — videos must land in the Video collection, or they vanish
            // from every video-only view after a restore.
            val mime = entry.mimeType
                ?: MediaFormats.mimeFor(entry.fileName)
                ?: "image/jpeg"
            val isVideo = MediaFormats.isVideoMime(mime)
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) "Movies/Deepix" else "Pictures/Deepix"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
            } else {
                if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }
            val out = context.contentResolver.insert(collection, values) ?: return false
            val written = context.contentResolver.openOutputStream(out)?.use { target ->
                src.inputStream().use { source -> source.copyTo(target) }
            } ?: return false
            if (written != src.length()) {
                // Half a file is worse than none: drop the row and keep the bin entry.
                runCatching { context.contentResolver.delete(out, null, null) }
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(out, values, null, null)
            }
            true
        }.getOrDefault(false)
    }

    private fun uniqueFile(desired: File): File {
        if (!desired.exists()) return desired
        val base = desired.nameWithoutExtension
        val ext = desired.extension
        var i = 1
        var candidate: File
        do {
            candidate = File(desired.parentFile, if (ext.isBlank()) "${base}_$i" else "${base}_$i.$ext")
            i++
        } while (candidate.exists())
        return candidate
    }

    private fun queryName(context: Context, uri: Uri): String? = queryColumn(context, uri, MediaStore.MediaColumns.DISPLAY_NAME)

    private fun querySize(context: Context, uri: Uri): Long? =
        queryColumn(context, uri, MediaStore.MediaColumns.SIZE)?.toLongOrNull()

    private fun queryColumn(context: Context, uri: Uri, column: String): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(column)
                    if (idx >= 0) return c.getString(idx)
                }
            }
            null
        }.getOrNull()
    }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)

    private fun android.content.ContentResolver.type(uri: Uri): String? =
        runCatching { getType(uri) }.getOrNull()
}
