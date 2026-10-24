package com.devomind.gallerysearch

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Writes copies of library photos into a folder album's own folder.
 *
 * The order of operations is the entire design: a photo is written, the copy is forced out to the
 * storage, then read back and counted, and only a copy whose length matches the original is reported as
 * landed. Callers delete originals from that report and nothing else, so a failed or partial write costs
 * the user a photo that did not make it into the album — never a photo that left the library.
 *
 * Images only. MediaStore expects a video row to sit under Movies rather than Pictures, and an album
 * folder lives under Pictures or Documents, so the picker that feeds this offers photos.
 */
object AlbumFolderOps {

    private const val TAG = "AlbumFolderOps"

    /**
     * @param copied pairs of original uri -> the new row that holds it, in the order they were picked.
     * @param failed originals nothing landed for. Their files were not touched.
     */
    data class Result(
        val copied: List<Pair<Uri, Uri>>,
        val failed: List<Uri>
    ) {
        val copiedSources: List<Uri> get() = copied.map { it.first }
    }

    /**
     * Whether a copy proves the photo is whole at the destination.
     *
     * Stricter than [BinLedger.copyComplete], which lets an unknown source size pass as "non-empty":
     * that one guards a bin entry nobody is about to delete anything for. Here the next step is removing
     * the original, so a size that cannot be named is a reason to stop rather than a weaker pass.
     */
    fun copyVerified(expectedBytes: Long, writtenBytes: Long): Boolean =
        expectedBytes > 0L && writtenBytes == expectedBytes

    /**
     * Copies [sources] into the album folder [relativePath] — a MediaStore-relative path such as
     * `Pictures/Pixa Albums/Trips` — reporting each photo as it finishes so the host can say which
     * number it is on. Runs off the main thread; the caller is on Dispatchers.IO.
     */
    fun copyInto(
        context: Context,
        sources: List<GalleryRepository.MediaItem>,
        relativePath: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result {
        if (sources.isEmpty()) return Result(emptyList(), emptyList())
        val sizes = sources.associate { it.uri to sizeOf(context, it.uri) }
        val needed = sources.sumOf { sizes[it.uri] ?: 0L }
        if (needed > 0L && !BinLedger.hasRoomFor(Environment.getExternalStorageDirectory().usableSpace, needed)) {
            // Refuse the batch before writing a byte. A volume that fills mid-run fails on whichever
            // photos happen to be last, which is a worse outcome than none of them moving.
            return Result(emptyList(), sources.map { it.uri })
        }
        val copied = mutableListOf<Pair<Uri, Uri>>()
        val failed = mutableListOf<Uri>()
        sources.forEachIndexed { index, item ->
            val target = runCatching { copyOne(context, item, relativePath, sizes[item.uri] ?: 0L) }
                .onFailure { Log.w(TAG, "Copy of ${item.uri} into $relativePath threw", it) }
                .getOrNull()
            if (target == null) failed += item.uri else copied += item.uri to target
            onProgress(index + 1, sources.size)
        }
        return Result(copied, failed)
    }

    private fun copyOne(
        context: Context,
        item: GalleryRepository.MediaItem,
        relativePath: String,
        expectedBytes: Long
    ): Uri? {
        if (expectedBytes <= 0L) return null
        val resolver = context.contentResolver
        val pendingSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val displayName = nameOf(context, item.uri) ?: File(item.path).name.takeIf { it.isNotBlank() }
            ?: "photo_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, resolver.getType(item.uri) ?: "image/jpeg")
            if (pendingSupported) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "$relativePath/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                // Pre-Q has no RELATIVE_PATH: the row is placed by its absolute DATA path, and a DATA path
                // that already belongs to another photo is written over, so the name has to be free first.
                val dir = publicDirFor(relativePath) ?: return null
                if (!dir.exists() && !dir.mkdirs()) return null
                put(MediaStore.MediaColumns.DATA, uniqueIn(dir, displayName).absolutePath)
            }
        }
        val collection = if (pendingSupported) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val target = resolver.insert(collection, values) ?: return null
        runCatching {
            resolver.openInputStream(item.uri)?.use { input ->
                resolver.openOutputStream(target)?.use { output -> input.copyTo(output) }
            }
        }.onFailure { Log.w(TAG, "Writing the copy at $target failed", it) }
            .getOrNull() ?: run {
            discard(resolver, target)
            return null
        }
        forceToDisk(context, target)
        val readBack = countBytes(context, target)
        if (!copyVerified(expectedBytes, readBack)) {
            Log.w(TAG, "Copy at $target read back as $readBack bytes, expected $expectedBytes")
            discard(resolver, target)
            return null
        }
        if (pendingSupported) {
            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            val result = runCatching { resolver.update(target, published, null, null) }
            if (result.isFailure) {
                // The copy exists but the rest of the gallery would still hide it, and the caller is about
                // to delete the original on the strength of this report. It has to be unsaid.
                Log.w(TAG, "Could not publish the copy at $target", result.exceptionOrNull())
                discard(resolver, target)
                return null
            }
        }
        return target
    }

    /**
     * Forces the copy's bytes onto the storage before they are counted, because the move path deletes
     * the original on the strength of this copy — bytes that only reached the page cache are not a copy
     * once the power goes. Same posture as [MediaFileOps.copyToFile]: a refused force is logged, not
     * fatal. Needs the path, which only All-files access can see; without it the copy stands unforced.
     */
    private fun forceToDisk(context: Context, uri: Uri) {
        if (!StoragePermissions.hasAllFilesAccess(context)) return
        val path = MediaFileOps.resolvePath(context, uri) ?: return
        runCatching { FileInputStream(File(path)).use { it.fd.sync() } }
            .onFailure { Log.w(TAG, "Sync of $path refused; the copy stands unforced", it) }
    }

    /** Reads the whole copy back and counts it — the check that makes deleting the original allowed. */
    private fun countBytes(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        } ?: -1L
    }.getOrDefault(-1L)

    private fun discard(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "Left a partial copy behind at $uri", it) }
    }

    private fun publicDirFor(relativePath: String): File? {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val publicDir = when (segments.first().lowercase()) {
            "pictures" -> Environment.DIRECTORY_PICTURES
            "documents" -> Environment.DIRECTORY_DOCUMENTS
            else -> return null
        }
        return File(
            Environment.getExternalStoragePublicDirectory(publicDir),
            segments.drop(1).joinToString(File.separator)
        )
    }

    private fun uniqueIn(dir: File, displayName: String): File {
        val desired = File(dir, displayName)
        if (!desired.exists()) return desired
        val base = desired.nameWithoutExtension
        val ext = desired.extension
        var index = 1
        while (true) {
            val candidate = File(dir, if (ext.isBlank()) "${base}_$index" else "${base}_$index.$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun nameOf(context: Context, uri: Uri): String? =
        queryColumn(context, uri, MediaStore.MediaColumns.DISPLAY_NAME)

    private fun sizeOf(context: Context, uri: Uri): Long =
        queryColumn(context, uri, MediaStore.MediaColumns.SIZE)?.toLongOrNull() ?: 0L

    private fun queryColumn(context: Context, uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(column)
                if (index >= 0) return cursor.getString(index)
            }
            null
        }
    }.getOrNull()
}
