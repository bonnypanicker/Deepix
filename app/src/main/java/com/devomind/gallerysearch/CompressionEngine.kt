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
 *   BACKED_UP         original verified-copied to [backupDir]; original still in place
 *   DEST_WRITTEN      verified compressed file sits next to the original; original still in place
 *   ORIGINAL_REMOVED  original deleted; only cleanup + MediaStore rescan remain
 *
 * [recover] runs on app start and settles any interrupted entry: originals that still exist keep
 * the photo (temp artifacts are removed); if the original is gone the destination is validated and
 * either finalized or restored from the backup. The destructive step (deleting the original) only
 * ever runs once TWO good copies exist on disk (backup + verified destination).
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
     * Replaces the original photo with the staged compressed file. Every intermediate state is
     * journaled BEFORE the next step runs, so a crash at any point is recoverable by [recover].
     * Requires All-files access (direct file operations); returns false without changing anything
     * when the original can't be resolved or verified.
     */
    fun commitReplace(context: Context, entry: Entry): Boolean {
        if (entry.originalPath.isBlank()) return false
        val original = File(entry.originalPath)
        val staging = File(entry.stagingPath)
        if (!original.exists() || !staging.exists()) return false

        // 1) Verified backup of the original inside app-private storage.
        val backup = File(entry.backupPath)
        backup.parentFile?.mkdirs()
        if (!copyFile(original, backup) || backup.length() != original.length()) {
            backup.delete()
            return false
        }
        upsertEntry(context, entry.withState(State.BACKED_UP))

        // 2) Verified compressed copy next to the original (original still untouched).
        val dest = File(entry.destPath)
        if (!writeVerifiedDest(staging, dest, original.lastModified())) {
            return false  // recovery or the caller's retry settles via BACKED_UP
        }
        upsertEntry(context, entry.withState(State.DEST_WRITTEN))

        // 3) Only now remove the original (two good copies exist: backup + dest).
        val removed = MediaFileOps.deleteFileDirect(context, Uri.parse(entry.originalUri)) || !original.exists()
        if (!removed) return false
        upsertEntry(context, entry.withState(State.ORIGINAL_REMOVED))

        // 4) Publish + clean up.
        MediaFileOps.rescan(context, entry.destPath)
        staging.delete()
        backup.delete()
        removeEntry(context, entry.id)
        return true
    }

    /** Keeps the original and saves the compressed copy next to it (or into Pictures/Deepix via
     *  MediaStore when the file path isn't accessible). Nothing destructive happens here. */
    fun commitCopy(context: Context, entry: Entry): Boolean {
        val staging = File(entry.stagingPath)
        if (!staging.exists()) return false

        if (entry.originalPath.isNotBlank() && entry.destPath.isNotBlank()) {
            val original = File(entry.originalPath)
            val dest = File(entry.destPath)
            if (!writeVerifiedDest(staging, dest, original.lastModified())) return false
            upsertEntry(context, entry.withState(State.COPY_WRITTEN))
            MediaFileOps.rescan(context, entry.destPath)
        } else {
            // No direct file access: insert through MediaStore into the app's pictures folder.
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
     * exists — a verified destination and, for a replacement, the original already gone — this
     * finishes the operation (rescan + drop temp artifacts + remove the journal entry) and returns
     * true, so the caller records success instead of re-running the destructive commit (which would
     * otherwise fail because the original no longer exists). Returns false when a real commit is
     * still needed. Never deletes an original or a good destination.
     */
    fun completeIfFinished(context: Context, entry: Entry, replace: Boolean): Boolean {
        if (entry.destPath.isBlank() || !verifies(File(entry.destPath))) return false
        if (replace && (entry.originalPath.isBlank() || File(entry.originalPath).exists())) return false
        MediaFileOps.rescan(context, entry.destPath)
        File(entry.stagingPath).delete()
        File(entry.backupPath).delete()
        removeEntry(context, entry.id)
        return true
    }

    /**
     * Narrower resume case: the journal entry was already removed by a completed run, but the batch
     * store hadn't recorded the outcome before the crash. Confirms the recorded destination is a
     * verified image (and, for a replacement, that the original is gone), rescans it, and reports
     * whether the photo is already committed on disk. Read-only — deletes nothing.
     */
    fun resultAlreadyOnDisk(context: Context, destPath: String, originalPath: String, replace: Boolean): Boolean {
        if (destPath.isBlank() || !verifies(File(destPath))) return false
        if (replace && (originalPath.isBlank() || File(originalPath).exists())) return false
        MediaFileOps.rescan(context, destPath)
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
        val original = File(entry.originalPath)
        val staging = File(entry.stagingPath)
        val backup = File(entry.backupPath)
        val dest = File(entry.destPath)
        when (entry.state) {
            // Nothing destructive happened: the original is the photo. Remove temp artifacts.
            State.PREPARED -> {
                staging.delete()
                if (dest.exists()) dest.delete()
                removeEntry(context, entry.id)
            }
            // Backup existed but the destination may be a partial write — delete it; the original
            // is still the user's photo.
            State.BACKED_UP -> {
                if (dest.exists()) {
                    dest.delete()
                    if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
                }
                staging.delete()
                backup.delete()
                removeEntry(context, entry.id)
            }
            State.DEST_WRITTEN -> when {
                // Original still there: safest outcome is keeping it and dropping the compressed copy.
                original.exists() -> {
                    dest.delete()
                    if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
                    staging.delete()
                    backup.delete()
                    removeEntry(context, entry.id)
                }
                // Original gone: finish the replacement if the destination is good…
                verifies(dest) -> {
                    if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
                    staging.delete()
                    backup.delete()
                    removeEntry(context, entry.id)
                }
                // …otherwise restore the photo from its backup.
                else -> restoreFromBackup(context, entry, staging, backup, dest)
            }
            State.ORIGINAL_REMOVED -> when {
                verifies(dest) -> {
                    if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
                    staging.delete()
                    backup.delete()
                    removeEntry(context, entry.id)
                }
                else -> restoreFromBackup(context, entry, staging, backup, dest)
            }
            State.COPY_WRITTEN -> {
                // Copy flow never destroys anything; a broken partial dest is simply removed.
                if (dest.exists() && !verifies(dest)) {
                    dest.delete()
                    if (entry.destPath.isNotBlank()) MediaFileOps.rescan(context, entry.destPath)
                } else if (verifies(dest) && entry.destPath.isNotBlank()) {
                    MediaFileOps.rescan(context, entry.destPath)
                }
                staging.delete()
                removeEntry(context, entry.id)
            }
        }
    }

    /** Last-resort path: the original was removed but the destination is missing/corrupt. */
    private fun restoreFromBackup(context: Context, entry: Entry, staging: File, backup: File, dest: File) {
        val original = File(entry.originalPath)
        var restored = false
        if (backup.exists() && entry.originalPath.isNotBlank()) {
            original.parentFile?.mkdirs()
            restored = copyFile(backup, original) && original.length() == backup.length()
        }
        if (restored) {
            MediaFileOps.rescan(context, entry.originalPath)
        } else {
            // Couldn't put the file back — KEEP the backup and the journal entry so the photo
            // bytes survive in app-private storage and a later recovery (or support) can retry.
            Log.e(TAG, "Backup restore failed for ${entry.originalUri}; keeping backup at ${backup.absolutePath}")
            return
        }
        if (dest.exists()) dest.delete()
        staging.delete()
        backup.delete()
        removeEntry(context, entry.id)
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
