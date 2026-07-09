package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-managed Recycle Bin. Deleted photos are copied into app-private storage ([binDir]) and the
 * original file is removed directly (requires All-files access — see [StoragePermissions]). Each
 * entry remembers where it came from so it can be restored to the same location, and anything older
 * than [RETENTION_MS] (30 days) is purged on app start.
 *
 * Storage is app-private (`filesDir/bin`), so binned photos are invisible to other galleries and are
 * cleared if the app is uninstalled — a deliberate privacy/simplicity trade-off.
 */
object BinManager {

    private const val TAG = "BinManager"
    private const val INDEX_NAME = "bin_index.json"
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    data class BinEntry(
        val id: String,
        val fileName: String,
        val originalPath: String?,
        val mimeType: String?,
        val deletedAt: Long,
        val sizeBytes: Long
    ) {
        fun storedFile(context: Context): File = File(binDir(context), id)
    }

    private fun binDir(context: Context): File =
        File(context.filesDir, "bin").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(binDir(context), INDEX_NAME)

    // ---- Move-in (delete) ----

    data class BinResult(val binned: Int, val failed: Int)

    /**
     * Copies each uri into the bin and deletes the original file directly. Requires All-files
     * access; without it, nothing is binned (caller should have checked and prompted).
     */
    fun moveToBin(context: Context, uris: List<Uri>): BinResult {
        if (!StoragePermissions.hasAllFilesAccess(context)) return BinResult(0, uris.size)
        val entries = loadEntries(context).toMutableList()
        var binned = 0
        var failed = 0
        for (uri in uris) {
            try {
                val path = MediaFileOps.resolvePath(context, uri)
                val display = queryName(context, uri) ?: path?.let { File(it).name } ?: "photo_${System.nanoTime()}"
                val mime = context.contentResolver.type(uri)
                val id = "${System.nanoTime()}_${sanitize(display)}"
                val dest = File(binDir(context), id)

                val copied = MediaFileOps.copyToFile(context, uri, dest)
                if (!copied) { failed++; continue }

                val deleted = MediaFileOps.deleteFileDirect(context, uri)
                if (!deleted) {
                    dest.delete()
                    failed++
                    continue
                }
                entries.add(
                    BinEntry(
                        id = id,
                        fileName = display,
                        originalPath = path,
                        mimeType = mime,
                        deletedAt = System.currentTimeMillis(),
                        sizeBytes = dest.length()
                    )
                )
                binned++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bin $uri", e)
                failed++
            }
        }
        saveEntries(context, entries)
        return BinResult(binned, failed)
    }

    // ---- Restore / delete-forever ----

    /** Writes a binned photo back to its original folder and removes it from the bin. */
    fun restore(context: Context, entry: BinEntry): Boolean {
        val src = entry.storedFile(context)
        if (!src.exists()) { removeEntry(context, entry.id); return false }
        val targetPath = entry.originalPath
        val restored = if (targetPath != null && StoragePermissions.hasAllFilesAccess(context)) {
            runCatching {
                val dest = uniqueFile(File(targetPath))
                src.copyTo(dest, overwrite = false)
                MediaFileOps.rescan(context, dest.absolutePath)
                true
            }.getOrDefault(false)
        } else {
            // Fallback: insert via MediaStore into Pictures/Deepix.
            insertViaMediaStore(context, src, entry)
        }
        if (restored) {
            src.delete()
            removeEntry(context, entry.id)
        }
        return restored
    }

    fun deleteForever(context: Context, entry: BinEntry): Boolean {
        entry.storedFile(context).delete()
        removeEntry(context, entry.id)
        return true
    }

    fun emptyBin(context: Context) {
        loadEntries(context).forEach { it.storedFile(context).delete() }
        saveEntries(context, emptyList())
    }

    /** Deletes bin entries older than the retention window. Call on app start. */
    fun purgeExpired(context: Context) {
        val now = System.currentTimeMillis()
        val kept = loadEntries(context).filter { entry ->
            val expired = now - entry.deletedAt > RETENTION_MS
            if (expired) entry.storedFile(context).delete()
            !expired
        }
        saveEntries(context, kept)
    }

    fun count(context: Context): Int = loadEntries(context).size

    /** Newest-first list for the Bin screen. */
    fun list(context: Context): List<BinEntry> =
        loadEntries(context).sortedByDescending { it.deletedAt }

    /** Content uri for displaying a binned file (via FileProvider). */
    fun contentUri(context: Context, entry: BinEntry): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.binprovider", entry.storedFile(context))

    // ---- Persistence ----

    private fun loadEntries(context: Context): List<BinEntry> {
        val file = indexFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BinEntry(
                    id = o.getString("id"),
                    fileName = o.optString("fileName"),
                    originalPath = o.optString("originalPath").ifBlank { null },
                    mimeType = o.optString("mimeType").ifBlank { null },
                    deletedAt = o.optLong("deletedAt"),
                    sizeBytes = o.optLong("sizeBytes")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveEntries(context: Context, entries: List<BinEntry>) {
        runCatching {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("fileName", e.fileName)
                        .put("originalPath", e.originalPath ?: "")
                        .put("mimeType", e.mimeType ?: "")
                        .put("deletedAt", e.deletedAt)
                        .put("sizeBytes", e.sizeBytes)
                )
            }
            indexFile(context).writeText(arr.toString())
        }.onFailure { Log.w(TAG, "Failed to save bin index", it) }
    }

    private fun removeEntry(context: Context, id: String) {
        saveEntries(context, loadEntries(context).filterNot { it.id == id })
    }

    // ---- Helpers ----

    private fun insertViaMediaStore(context: Context, src: File, entry: BinEntry): Boolean {
        return runCatching {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, entry.fileName)
                put(MediaStore.Images.Media.MIME_TYPE, entry.mimeType ?: "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Deepix")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val out = context.contentResolver.insert(collection, values) ?: return false
            context.contentResolver.openOutputStream(out)?.use { os -> src.inputStream().use { it.copyTo(os) } }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
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

    private fun queryName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
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
