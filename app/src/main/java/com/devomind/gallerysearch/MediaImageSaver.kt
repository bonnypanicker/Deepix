package com.devomind.gallerysearch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream
import java.io.File

/**
 * Writes edited bitmaps back to MediaStore.
 *
 * - [saveCopy] always works (inserts a new image in Pictures/Deepix).
 * - [overwrite] rewrites the original; on Android 10+ that can need the user's write consent, which
 *   surfaces as a [android.app.RecoverableSecurityException] the caller resolves via an IntentSender.
 */
object MediaImageSaver {

    private const val COPY_FOLDER = "Deepix"

    fun compress(bitmap: Bitmap, out: OutputStream, jpeg: Boolean) {
        if (jpeg) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** Rewrites the original image. May throw RecoverableSecurityException on Q+; caller retries. */
    fun overwrite(context: Context, uri: Uri, bitmap: Bitmap) {
        // PNG keeps lossless/alpha sources lossless; everything else (JPEG, HEIC, WebP, BMP,
        // TIFF…) re-encodes as JPEG — never PNG, which would balloon photographic content.
        val resolver = context.contentResolver
        val png = resolver.getType(uri).equals("image/png", ignoreCase = true)
        val work = File(context.cacheDir, "editor_overwrite").apply { mkdirs() }
        val encoded = File.createTempFile("edited_", if (png) ".png" else ".jpg", work)
        val backup = File.createTempFile("original_", ".bak", work)
        try {
            encoded.outputStream().use { compress(bitmap, it, jpeg = !png) }
            if (BitmapFactory.decodeFile(encoded.absolutePath) == null) {
                throw IllegalStateException("Edited image could not be verified")
            }
            resolver.openInputStream(uri)?.use { input ->
                backup.outputStream().use { output -> input.copyTo(output) }
            }
                ?: throw IllegalStateException("Could not back up $uri")
            try {
                resolver.openOutputStream(uri, "wt")?.use { output ->
                    encoded.inputStream().use { input -> input.copyTo(output) }
                }
                    ?: throw IllegalStateException("Could not open $uri for writing")
            } catch (writeFailure: Exception) {
                runCatching {
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        backup.inputStream().use { input -> input.copyTo(output) }
                    }
                }
                throw writeFailure
            }
        } finally {
            encoded.delete()
            backup.delete()
        }
    }

    /** Inserts a new image next to the app's pictures. Returns the new content uri. */
    fun saveCopy(context: Context, bitmap: Bitmap, baseName: String): Uri {
        val resolver = context.contentResolver
        val name = "${baseName}_edited_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$COPY_FOLDER")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val newUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to create new image entry")
        resolver.openOutputStream(newUri)?.use { out ->
            compress(bitmap, out, jpeg = true)
        } ?: throw IllegalStateException("Failed to open new image for writing")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(newUri, values, null, null)
        }
        return newUri
    }
}
