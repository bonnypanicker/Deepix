package com.devomind.gallerysearch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * HEIC compression engine for Smart Cleanup, built so a crash or process death can NEVER cost a
 * photo. Every replacement runs as a write-ahead state machine persisted to [journalFile] after
 * each step:
 *
 *   PREPARED          compressed copy exists in [stagingDir]; original untouched
 *   BACKED_UP         original verified-copied to [backupDir]; overwrite not yet confirmed
 *   DEST_WRITTEN      the original's own MediaStore row was overwritten with the compressed bytes
 *   ORIGINAL_REMOVED  (legacy) the original was removed rather than overwritten
 *
 * [recover] runs on app start and settles any interrupted entry. A replacement never deletes the
 * original — it overwrites the original's own row, so the photo keeps its place in the library —
 * which makes "did the overwrite land?" a question about that row's byte count: the original's size
 * means it never landed, the compressed size means it did, and anything else is a torn write whose
 * only remaining copy is the app-private backup (written back over the original, so the photo is
 * whole again).
 *
 * Output is HEIC via androidx HeifWriter on API 28+; devices without an HEVC encoder (or where the
 * encode fails) transparently fall back to lossy WebP, which the platform can always decode.
 */
object CompressionEngine {

    private const val TAG = "CompressionEngine"
    private const val DIR_NAME = "compression"
    private const val JOURNAL_NAME = "journal.json"
    private const val STAGING_DIR = "staging"
    private const val BACKUP_DIR = "backup"
    private const val MAX_DIMENSION = 8192          // HEVC encoders commonly cap at 8192x8192
    private const val SMALL_MAX_DIMENSION = 2048    // "Smaller file" preset: ~3MP, plenty for screens
    private const val STOP_TIMEOUT_MS = 30_000L
    private const val ORPHAN_AGE_MS = 24 * 60 * 60 * 1000L

    /** Smallest file worth re-encoding; below this the absolute gain is noise. */
    const val MIN_COMPRESSIBLE_BYTES = 1024L * 1024L

    enum class Format(val extension: String, val mimeType: String) {
        HEIC("heic", "image/heic"),
        WEBP("webp", "image/webp")
    }

    enum class State { PREPARED, BACKED_UP, DEST_WRITTEN, ORIGINAL_REMOVED, COPY_WRITTEN }

    enum class Mode { REPLACE, COPY }

    /**
     * A quality preset. HEIC/WebP `quality` alone barely moves file size on many hardware
     * encoders, so the presets also differ in [maxDimension]: the encoder caps are generous
     * (8192 = visually lossless, no downscale) while "smaller file" also downscales the
     * bitmap's longest edge, which cuts real megabytes for a modest resolution loss.
     */
    data class Preset(val quality: Int, val maxDimension: Int)

    object Presets {
        val HIGH = Preset(quality = 90, maxDimension = MAX_DIMENSION)
        val BALANCED = Preset(quality = 80, maxDimension = MAX_DIMENSION)
        val SMALL = Preset(quality = 65, maxDimension = SMALL_MAX_DIMENSION)
    }

    /** One journaled compression operation. All paths are absolute; [originalPath] may be blank
     *  when the file location couldn't be resolved (MediaStore-only copy flow). */
    data class Entry(
        val id: String,
        val originalUri: String,
        val originalPath: String,
        val displayName: String,
        val destPath: String,
        val stagingPath: String,
        val backupPath: String,
        val format: Format,
        val mode: Mode,
        val quality: Int,
        val sizeBefore: Long,
        val sizeAfter: Long,
        val state: State,
        val createdAt: Long
    ) {
        fun withState(state: State, destPath: String = this.destPath) =
            copy(state = state, destPath = destPath)
    }

    private val lock = Any()

    private fun rootDir(context: Context) = File(context.filesDir, DIR_NAME)
    private fun stagingDir(context: Context) = File(rootDir(context), STAGING_DIR)
    private fun backupDir(context: Context) = File(rootDir(context), BACKUP_DIR)
    private fun journalFile(context: Context) = File(rootDir(context), JOURNAL_NAME)

    // ----------------------------------------------------------------------------------
    // Candidate selection + estimates (used by CleanupAnalyzer and the UI)
    // ----------------------------------------------------------------------------------

    /** Formats that re-encode to HEIC/WebP with a real win. HEIC/HEIF/AVIF are already efficient;
     *  GIFs may be animated (re-encoding would drop frames), so both are excluded. */
    fun isCompressibleMime(mimeType: String?): Boolean = when (mimeType?.lowercase(Locale.US)) {
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/bmp", "image/x-ms-bmp" -> true
        else -> false
    }

    /** Rough expected savings, only for tile/summary display — the review screen shows exact sizes. */
    fun estimatedSavings(mimeType: String?, sizeBytes: Long): Long {
        val ratio = when (mimeType?.lowercase(Locale.US)) {
            "image/png" -> 0.65
            "image/bmp", "image/x-ms-bmp" -> 0.9
            "image/webp" -> 0.3
            else -> 0.5
        }
        return (sizeBytes * ratio).toLong()
    }

    // ----------------------------------------------------------------------------------
    // Journal
    // ----------------------------------------------------------------------------------

    fun loadJournal(context: Context): List<Entry> = synchronized(lock) {
        val file = journalFile(context)
        if (!file.exists()) return emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Entry(
                    id = o.getString("id"),
                    originalUri = o.getString("originalUri"),
                    originalPath = o.optString("originalPath", ""),
                    displayName = o.optString("displayName", ""),
                    destPath = o.optString("destPath", ""),
                    stagingPath = o.getString("stagingPath"),
                    backupPath = o.optString("backupPath", ""),
                    format = Format.valueOf(o.getString("format")),
                    mode = Mode.valueOf(o.optString("mode", Mode.REPLACE.name)),
                    quality = o.optInt("quality", 80),
                    sizeBefore = o.optLong("sizeBefore", 0L),
                    sizeAfter = o.optLong("sizeAfter", 0L),
                    state = State.valueOf(o.getString("state")),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }
        }.onFailure { Log.w(TAG, "Failed to read compression journal.", it) }.getOrDefault(emptyList())
    }

    private fun saveJournal(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(JSONObject().apply {
                put("id", e.id)
                put("originalUri", e.originalUri)
                put("originalPath", e.originalPath)
                put("displayName", e.displayName)
                put("destPath", e.destPath)
                put("stagingPath", e.stagingPath)
                put("backupPath", e.backupPath)
                put("format", e.format.name)
                put("mode", e.mode.name)
                put("quality", e.quality)
                put("sizeBefore", e.sizeBefore)
                put("sizeAfter", e.sizeAfter)
                put("state", e.state.name)
                put("createdAt", e.createdAt)
            })
        }
        val file = journalFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$JOURNAL_NAME.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString())
            tmp.delete()
        }
    }

    private fun upsertEntry(context: Context, entry: Entry) = synchronized(lock) {
        val entries = loadJournal(context).filterNot { it.id == entry.id } + entry
        runCatching { saveJournal(context, entries) }
            .onFailure { Log.w(TAG, "Failed to persist compression journal.", it) }
    }

    private fun removeEntry(context: Context, id: String) = synchronized(lock) {
        val entries = loadJournal(context).filterNot { it.id == id }
        runCatching { saveJournal(context, entries) }
            .onFailure { Log.w(TAG, "Failed to persist compression journal.", it) }
    }

    // ----------------------------------------------------------------------------------
    // Step 1 — encode to staging (never touches the original)
    // ----------------------------------------------------------------------------------

    /**
     * Encodes [uri] to a verified compressed file in staging and journals it as [State.PREPARED].
     * [preset] carries both the encoder quality and the longest-edge cap (the "smaller file"
     * preset downscales). Returns the entry on success, null when the photo can't be decoded
     * or encoded.
     */
    fun prepare(context: Context, uri: Uri, displayName: String?, quality: Int, mode: Mode, maxDimension: Int = MAX_DIMENSION): Entry? {
        if (!isCompressibleMime(context.contentResolver.getType(uri))) return null
        val bitmap = decodeOriented(context, uri, maxDimension) ?: return null
        val id = "${System.currentTimeMillis()}_${System.nanoTime()}"
        val originalPath = MediaFileOps.resolvePath(context, uri).orEmpty()
        val sizeBefore = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        }.getOrDefault(0L).takeIf { it > 0L } ?: File(originalPath).length()

        var format = Format.HEIC
        var staging = File(stagingDir(context), "$id.heic")
        stagingDir(context).mkdirs()
        val heicOk = encodeHeic(bitmap, quality, staging)
        if (!heicOk) {
            format = Format.WEBP
            staging = File(stagingDir(context), "$id.webp")
            if (!encodeWebp(bitmap, quality, staging)) {
                bitmap.recycle()
                return null
            }
        }
        bitmap.recycle()

        if (!verifies(staging)) {
            staging.delete()
            return null
        }

        val destPath = if (originalPath.isNotBlank()) {
            uniqueDestPath(originalPath, displayName, format)
        } else {
            ""
        }
        val backupPath = File(backupDir(context), "$id.backup").absolutePath
        val entry = Entry(
            id = id,
            originalUri = uri.toString(),
            originalPath = originalPath,
            displayName = displayName.orEmpty(),
            destPath = destPath,
            stagingPath = staging.absolutePath,
            backupPath = backupPath,
            format = format,
            mode = mode,
            quality = quality,
            sizeBefore = sizeBefore,
            sizeAfter = staging.length(),
            state = State.PREPARED,
            createdAt = System.currentTimeMillis()
        )
        upsertEntry(context, entry)
        return entry
    }

    // ----------------------------------------------------------------------------------
    // Step 2 — commit (replace the original, or keep both)
    // ----------------------------------------------------------------------------------

    /**
     * Replaces the original photo with the staged compressed file by overwriting it where it already
     * is, so the photo keeps its path, its MediaStore row and its URI — callers that track the
     * original URI (Smart Cleanup's lists, the batch's `replacedUris`) stay valid.
     *
     * On API 30+ the overwrite goes through the original's own row via `openOutputStream`, which
     * needs the user's write consent (`MediaStore.createWriteRequest`). That consent is collected by
     * the review screen before it enqueues this pass, because the background worker that runs it
     * cannot raise the system dialog. Below API 30 the legacy write permission still covers a direct
     * overwrite in place, so no consent is involved.
     *
     * Every intermediate state is journaled BEFORE the next step runs, so a crash at any point is
     * recoverable by [recover]. The original is never deleted: a verified byte backup is taken into
     * app-private storage first, and if the overwrite does not read back as the compressed size that
     * backup is what the photo is repaired from. The flag is re-checked here so a batch persisted by
     * an older build can never resume into an overwrite. Returns false when the flag is off, the
     * staged file is gone, or the overwrite could not be verified.
     */
    fun commitReplace(context: Context, entry: Entry): Boolean {
        if (!FeatureFlags.COMPRESSION_REPLACE_ENABLED) return false
        val staging = File(entry.stagingPath)
        if (!staging.exists()) return false
        val originalUri = Uri.parse(entry.originalUri)

        // 1) Verified byte backup of the original in app-private storage. This is the copy the photo
        //    is rebuilt from if the overwrite tears, so it is checked, not assumed.
        val backup = File(entry.backupPath)
        backup.delete()
        if (!backupOriginal(context, originalUri, backup)) {
            backup.delete()
            return false
        }
        upsertEntry(context, entry.withState(State.BACKED_UP))

        // 2) Overwrite the original in place, then confirm the new bytes actually landed.
        if (!overwriteOriginal(context, entry, originalUri, staging)) {
            // The write may have torn. Repair immediately rather than leaving a half-written photo
            // in the library until the next app start.
            repairOriginal(context, entry, backup)
            return false
        }
        upsertEntry(context, entry.withState(State.DEST_WRITTEN))

        // 3) Publish + clean up. The URI never changed, so only the index needs to catch up.
        if (entry.originalPath.isNotBlank()) MediaFileOps.rescan(context, entry.originalPath)
        staging.delete()
        backup.delete()
        removeEntry(context, entry.id)
        return true
    }

    /** Keeps the original and saves the compressed copy. On API 26-28 the copy is written next to the
     *  original via direct file I/O (WRITE_EXTERNAL_STORAGE covers it). API 29+ enforces scoped
     *  storage, so the copy goes through MediaStore insert into Pictures/Deepix with RELATIVE_PATH. */
    fun commitCopy(context: Context, entry: Entry): Boolean {
        val staging = File(entry.stagingPath)
        if (!staging.exists()) return false

        // Direct file write to shared storage only works on API 26-28. API 29+ enforces scoped
        // storage, so insert through MediaStore instead of trying (and failing) to write the file.
        val canWriteDirect = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (canWriteDirect && entry.originalPath.isNotBlank() && entry.destPath.isNotBlank()) {
            val original = File(entry.originalPath)
            val dest = File(entry.destPath)
            if (!writeVerifiedDest(staging, dest, original.lastModified())) return false
            upsertEntry(context, entry.withState(State.COPY_WRITTEN))
            MediaFileOps.rescan(context, entry.destPath)
        } else {
            if (!insertViaMediaStore(context, staging, entry)) return false
        }
        staging.delete()
        removeEntry(context, entry.id)
        return true
    }

    /** User abandoned this preparation: drop the staged file and its journal record. */
    fun discard(context: Context, entry: Entry) {
        File(entry.stagingPath).delete()
        File(entry.backupPath).delete()
        removeEntry(context, entry.id)
    }

    /**
     * Resume helper for a commit interrupted by a crash. When the final on-disk result already
     * exists — for a replacement, the original's own row already holding the compressed bytes; for a
     * copy, a verified destination — this finishes the operation (rescan + drop temp artifacts +
     * remove the journal entry) and returns true, so the caller records success instead of re-running
     * the commit. Returns false when a real commit is still needed. Never deletes an original or a
     * good destination.
     */
    fun completeIfFinished(context: Context, entry: Entry, replace: Boolean): Boolean {
        if (replace) {
            if (entry.sizeAfter <= 0L || onDiskSize(context, Uri.parse(entry.originalUri)) != entry.sizeAfter) {
                return false
            }
            if (entry.originalPath.isNotBlank()) MediaFileOps.rescan(context, entry.originalPath)
            File(entry.stagingPath).delete()
            File(entry.backupPath).delete()
            removeEntry(context, entry.id)
            return true
        }
        if (entry.destPath.isBlank() || !verifies(File(entry.destPath))) return false
        MediaFileOps.rescan(context, entry.destPath)
        File(entry.stagingPath).delete()
        File(entry.backupPath).delete()
        removeEntry(context, entry.id)
        return true
    }

    /**
     * Narrower resume case: the journal entry was already removed by a completed run, but the batch
     * store hadn't recorded the outcome before the crash. Confirms the recorded result is really on
     * disk — for a replacement, the original's row now holding the compressed size; for a copy, a
     * verified destination — and reports whether the photo is already committed. Read-only.
     */
    fun resultAlreadyOnDisk(
        context: Context,
        originalUri: String,
        destPath: String,
        sizeAfter: Long,
        replace: Boolean
    ): Boolean {
        if (replace) {
            if (sizeAfter <= 0L || onDiskSize(context, Uri.parse(originalUri)) != sizeAfter) return false
        } else if (destPath.isBlank() || !verifies(File(destPath))) {
            return false
        }
        val path = if (replace) MediaFileOps.resolvePath(context, Uri.parse(originalUri)) else destPath
        if (!path.isNullOrBlank()) MediaFileOps.rescan(context, path)
        return true
    }

    // ----------------------------------------------------------------------------------
    // Crash recovery — runs on every app start (cheap no-op when the journal is empty)
    // ----------------------------------------------------------------------------------

    fun recover(context: Context) {
        val entries = loadJournal(context)
        if (entries.isEmpty()) {
            sweepOrphans(context)
            return
        }
        // Entries owned by an active background batch belong to CompressionWorker, which resumes
        // them (commit or discard) — settling them here would drop prepared work it still needs.
        val ownedByActiveBatch = runCatching {
            CompressionBatchStore(context).load()
                ?.takeIf { it.isActive }
                ?.items?.mapNotNullTo(HashSet()) { it.entryId.takeIf(String::isNotBlank) }
                ?: emptySet()
        }.getOrDefault(emptySet())
        for (entry in entries) {
            if (entry.id in ownedByActiveBatch) continue
            runCatching { settle(context, entry) }
                .onFailure { Log.w(TAG, "Recovery failed for ${entry.id}", it) }
        }
        sweepOrphans(context)
    }

    private fun settle(context: Context, entry: Entry) {
        val staging = File(entry.stagingPath)
        val backup = File(entry.backupPath)
        val dest = File(entry.destPath)
        when (entry.state) {
            State.PREPARED -> settlePrepared(context, entry, staging, dest)
            State.BACKED_UP -> settleBackedUp(context, entry, staging, backup, dest)
            State.DEST_WRITTEN -> settleDestWritten(context, entry, staging, backup)
            State.ORIGINAL_REMOVED -> settleOriginalRemoved(context, entry, staging, backup, dest)
            State.COPY_WRITTEN -> settleCopyWritten(context, entry, staging, dest)
        }
    }

    /** Nothing destructive happened: the original is the photo. Remove temp artifacts. */
    private fun settlePrepared(context: Context, entry: Entry, staging: File, dest: File) {
        staging.delete()
        if (dest.exists()) dest.delete()
        removeEntry(context, entry.id)
    }

    /**
     * The backup is taken, but the original may or may not have been overwritten before the crash,
     * so its own row decides: its pre-replacement size means untouched, and anything else is settled
     * by [repairOriginal] (which restores from the backup if the write tore).
     */
    private fun settleBackedUp(context: Context, entry: Entry, staging: File, backup: File, dest: File) {
        val landed = onDiskSize(context, Uri.parse(entry.originalUri))
        if (entry.sizeBefore > 0L && landed == entry.sizeBefore) {
            // A legacy entry may have left a sibling copy beside the original; drop it.
            if (dest.exists()) {
                dest.delete()
                if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
            }
            dropJournal(context, entry, staging, backup)
        } else {
            repairOriginal(context, entry, backup)
        }
    }

    /**
     * The overwrite was verified when it was journaled; re-confirm and, if the bytes are no longer
     * there, put the original back from the backup.
     */
    private fun settleDestWritten(context: Context, entry: Entry, staging: File, backup: File) {
        val landed = onDiskSize(context, Uri.parse(entry.originalUri))
        if (entry.sizeAfter > 0L && landed == entry.sizeAfter) {
            if (entry.originalPath.isNotBlank()) MediaFileOps.rescan(context, entry.originalPath)
            dropJournal(context, entry, staging, backup)
        } else {
            repairOriginal(context, entry, backup)
        }
    }

    /**
     * Legacy entry from the direct-file build: the original had been removed and the compressed copy
     * written beside it. Keep the copy when it is good, otherwise put the original back.
     */
    private fun settleOriginalRemoved(
        context: Context,
        entry: Entry,
        staging: File,
        backup: File,
        dest: File
    ) {
        if (verifies(dest)) {
            if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
            dropJournal(context, entry, staging, backup)
        } else if (restoreOriginal(context, entry, backup)) {
            dropJournal(context, entry, staging, backup)
        } else {
            // Couldn't put the file back — KEEP the backup and the journal entry so the photo bytes
            // survive in app-private storage for a later recovery.
            Log.e(TAG, "Backup restore failed for ${entry.originalUri}; keeping backup at ${backup.absolutePath}")
        }
    }

    /** Copy flow never destroys anything; a broken partial destination is simply removed. */
    private fun settleCopyWritten(context: Context, entry: Entry, staging: File, dest: File) {
        if (dest.exists() && !verifies(dest)) {
            dest.delete()
            if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
        } else if (verifies(dest) && entry.destPath.isNotBlank()) {
            MediaFileOps.rescan(context, entry.destPath)
        }
        staging.delete()
        removeEntry(context, entry.id)
    }

    /** Drops a settled entry's journal record and its app-private temp artifacts. */
    private fun dropJournal(context: Context, entry: Entry, staging: File, backup: File) {
        staging.delete()
        backup.delete()
        removeEntry(context, entry.id)
    }

    /**
     * Settles an interrupted replacement by looking at the original's byte count: the pre-replacement
     * size means the overwrite never landed, the compressed size means it landed after all, and
     * anything else is a torn write whose only good copy is the app-private [backup] — written back
     * over the original so the photo is whole again. If even that fails, the backup and the journal
     * entry are kept, so the bytes survive for a later recovery instead of being thrown away.
     */
    private fun repairOriginal(context: Context, entry: Entry, backup: File) {
        val landed = onDiskSize(context, Uri.parse(entry.originalUri))
        val intact = (entry.sizeBefore > 0L && landed == entry.sizeBefore) ||
            (entry.sizeAfter > 0L && landed == entry.sizeAfter)
        if (intact || restoreOriginal(context, entry, backup)) {
            if (entry.originalPath.isNotBlank()) MediaFileOps.rescan(context, entry.originalPath)
            dropJournal(context, entry, File(entry.stagingPath), backup)
            return
        }
        Log.e(TAG, "Restore failed for ${entry.originalUri}; keeping the backup at ${backup.absolutePath}")
    }

    /** Writes [backup]'s bytes back over the original, whichever way this device allows. */
    private fun restoreOriginal(context: Context, entry: Entry, backup: File): Boolean {
        if (backup.length() <= 0L) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overwriteRow(context, Uri.parse(entry.originalUri), backup)
        } else {
            entry.originalPath.isNotBlank() && overwriteFile(backup, File(entry.originalPath))
        }
    }

    /** Removes staged files that have no journal entry (e.g. written just before a crash). */
    private fun sweepOrphans(context: Context) {
        val known = loadJournal(context).mapTo(HashSet()) { it.stagingPath }
        val now = System.currentTimeMillis()
        stagingDir(context).listFiles()?.forEach { file ->
            if (file.absolutePath !in known && now - file.lastModified() > ORPHAN_AGE_MS) {
                file.delete()
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // Encoding
    // ----------------------------------------------------------------------------------

    private fun encodeHeic(bitmap: Bitmap, quality: Int, dest: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val thread = HandlerThread("heic-encode").apply { start() }
        return try {
            val writer = HeifWriter.Builder(
                dest.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP
            )
                .setQuality(quality)
                .setHandler(Handler(thread.looper))
                .build()
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(STOP_TIMEOUT_MS)
            writer.close()
            dest.length() > 0L
        } catch (error: Throwable) {
            Log.w(TAG, "HEIC encode failed; caller falls back to WebP.", error)
            dest.delete()
            false
        } finally {
            thread.quitSafely()
        }
    }

    private fun encodeWebp(bitmap: Bitmap, quality: Int, dest: File): Boolean {
        return runCatching {
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
                }
            }
            dest.length() > 0L
        }.getOrDefault(false)
    }

    /** Full-resolution decode capped at [maxDim] (longest edge), with the EXIF orientation baked
     *  in so the compressed file displays upright even without metadata. */
    private fun decodeOriented(context: Context, uri: Uri, maxDim: Int = MAX_DIMENSION): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2

        var bitmap = decodeSampled(context, uri, sample)
        if (bitmap == null && sample > 1) {
            // Memory pressure can kill large decodes; retry once at half resolution.
            bitmap = decodeSampled(context, uri, sample * 2)
        }
        bitmap ?: return null

        // Sampling only works in powers of two, so the decoded edge can still overshoot the
        // cap (e.g. 8192-target on a 9000px photo decodes at 4500… or 2048-cap lands at 4096).
        // Squeeze the longest edge to exactly [maxDim], preserving aspect ratio.
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxDim) {
            val scale = maxDim.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true
            )
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }
        return bitmap
    }

    private fun decodeSampled(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "Decode OOM at sampleSize=$sampleSize", oom)
            null
        }
    }

    // ----------------------------------------------------------------------------------
    // File helpers
    // ----------------------------------------------------------------------------------

    /** Confirms a compressed artifact is real: non-empty and header-decodable. */
    private fun verifies(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
            .isSuccess && opts.outWidth > 0 && opts.outHeight > 0
    }

    /** Copies staging → dest and verifies the result before reporting success. */
    private fun writeVerifiedDest(staging: File, dest: File, lastModified: Long): Boolean {
        if (!copyFile(staging, dest)) return false
        dest.setLastModified(if (lastModified > 0L) lastModified else System.currentTimeMillis())
        if (dest.length() != staging.length() || !verifies(dest)) {
            dest.delete()
            return false
        }
        return true
    }

    /** Overwrites [dest] with [staging]'s bytes and verifies the result. Unlike [writeVerifiedDest]
     *  this never deletes [dest] on failure: [dest] may BE the user's original, and a failed
     *  overwrite is repaired from the backup, not by deleting the photo. */
    private fun overwriteFile(staging: File, dest: File): Boolean {
        val lastModified = dest.lastModified()
        if (!copyFile(staging, dest)) return false
        dest.setLastModified(if (lastModified > 0L) lastModified else System.currentTimeMillis())
        return dest.length() == staging.length() && verifies(dest)
    }

    /** Verified byte copy of the original into [backup] (app-private). Reads through MediaStore, so
     *  it needs neither a filesystem path nor write access to the original. */
    private fun backupOriginal(context: Context, uri: Uri, backup: File): Boolean {
        val expected = onDiskSize(context, uri)
        if (expected <= 0L) return false
        if (!MediaFileOps.copyToFile(context, uri, backup)) return false
        return backup.length() == expected && verifies(backup)
    }

    /** Overwrites the original in place. On API 30+ that is the original's own MediaStore row (under
     *  the user's write consent); below API 30 the legacy write permission still covers a direct
     *  overwrite of the file. */
    private fun overwriteOriginal(context: Context, entry: Entry, uri: Uri, staging: File): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overwriteRow(context, uri, staging)
        } else {
            entry.originalPath.isNotBlank() && overwriteFile(staging, File(entry.originalPath))
        }

    /** Writes [source]'s bytes over the media row at [uri], flushes them, and confirms by reading
     *  the row's length back. "wt" truncates, so a smaller file does not leave the old tail behind. */
    private fun overwriteRow(context: Context, uri: Uri, source: File): Boolean {
        val expected = source.length()
        if (expected <= 0L) return false
        val written = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
                out.flush()
            } ?: return false
            true
        }.getOrElse {
            Log.w(TAG, "Overwrite of $uri failed; recovery settles it from the backup.", it)
            return false
        }
        if (!written) return false
        // Push the bytes to disk: "the overwrite landed" must not be just a page-cache claim.
        runCatching { context.contentResolver.openFileDescriptor(uri, "rw")?.use { it.fileDescriptor.sync() } }
            .onFailure { Log.w(TAG, "Sync of $uri refused; the overwrite stands unforced", it) }
        return onDiskSize(context, uri) == expected
    }

    /** The bytes actually on disk for [uri], read through MediaStore. -1 when it can't be resolved. */
    private fun onDiskSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)

    private fun copyFile(from: File, to: File): Boolean = runCatching {
        to.parentFile?.mkdirs()
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        true
    }.getOrDefault(false)

    /** Destination path next to the original: same base name with the new extension, de-conflicted. */
    private fun uniqueDestPath(originalPath: String, displayName: String?, format: Format): String {
        val original = File(originalPath)
        val dir = original.parentFile ?: return ""
        val base = (displayName ?: original.name).substringBeforeLast('.')
            .ifBlank { "IMG_${System.currentTimeMillis()}" }
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        var candidate = File(dir, "$base.${format.extension}")
        var n = 1
        while (candidate.exists() || candidate.absolutePath == original.absolutePath) {
            candidate = File(dir, "${base}_$n.${format.extension}")
            n++
        }
        return candidate.absolutePath
    }

    /** MediaStore insert for the keep-both flow when direct file access isn't available. */
    private fun insertViaMediaStore(context: Context, staging: File, entry: Entry): Boolean {
        val resolver = context.contentResolver
        val name = (entry.displayName.substringBeforeLast('.').ifBlank { "IMG_${entry.createdAt}" }) +
            ".${entry.format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, entry.format.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Deepix")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return runCatching {
            val newUri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(newUri)?.use { out ->
                staging.inputStream().use { it.copyTo(out) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(newUri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }
}
