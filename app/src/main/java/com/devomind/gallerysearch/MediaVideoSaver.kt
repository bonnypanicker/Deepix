package com.devomind.gallerysearch

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Writes exported video files back to MediaStore, mirroring [MediaImageSaver]:
 * - [saveCopy] always works (inserts a new video in Movies/Deepix).
 * - [overwrite] rewrites the original from the exported temp file; on Android 10+ that can need
 *   the user's write consent, which the caller resolves via an IntentSender and retries.
 */
object MediaVideoSaver {

    private const val COPY_FOLDER = "Deepix"

    /** Streams [source]'s bytes over the original video. May throw RecoverableSecurityException. */
    fun overwrite(context: Context, uri: Uri, source: File, durationMs: Long) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not open $uri for writing")
        context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Video.Media.SIZE, source.length())
            put(MediaStore.Video.Media.DURATION, durationMs)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        }, null, null)
    }

    /** Inserts the exported file as a new video. Returns the new content uri. */
    fun saveCopy(context: Context, source: File, baseName: String, durationMs: Long): Uri {
        val resolver = context.contentResolver
        val name = "${baseName.substringBeforeLast('.')}_edited_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.SIZE, source.length())
            put(MediaStore.Video.Media.DURATION, durationMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$COPY_FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val newUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to create new video entry")
        resolver.openOutputStream(newUri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Failed to open new video for writing")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(newUri, values, null, null)
        }
        return newUri
    }
}
