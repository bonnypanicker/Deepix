package com.devomind.gallerysearch

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Publishes the image library over the Storage Access Framework, which is what puts Pixa in the
 * system file picker's drawer — the same slot the stock "Images" root and gallery apps like Google
 * Photos occupy. The picker only ever talks to [DocumentsProvider]s, so without this entry every
 * "choose a photo" flow (a website file input, another app's attach button) cannot reach the
 * library: the app simply is not offered as a source.
 *
 * Access is gated by `android.permission.MANAGE_DOCUMENTS`, a signature permission held by the system
 * picker alone, so no ordinary app can read the library through here. The provider is read-only: a
 * document id is a MediaStore id and [openDocument] returns a descriptor for the original bytes, so a
 * page receives the untouched file rather than a re-encoded copy. [openDocumentThumbnail] feeds the
 * picker's grid; it is best-effort, and any failure throws out of it so the grid falls back to a
 * format icon instead of showing a broken cell.
 */
class PixaDocumentsProvider : DocumentsProvider() {

    private companion object {
        /** Root id, which the SAF contract also reuses as the document id of the root itself. */
        const val ROOT_ID = "pixa-images"

        const val MIME_IMAGE_ALL = "image/*"
        const val FALLBACK_IMAGE_MIME = "image/jpeg"
        const val SECONDS_TO_MILLIS = 1000L

        /** Where generated grid thumbnails live. Cache: the system may clear it at any time. */
        const val THUMB_DIR = "picker-thumbs"
        const val THUMB_QUALITY = 80

        /** A picker normally asks for ~512px; clamp so neither a tiny nor a wild hint costs us. */
        const val THUMB_MIN_EDGE = 128
        const val THUMB_MAX_EDGE = 1024

        /** Newest first, matching the order the app's own grids use. */
        val NEWEST_FIRST = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        /** The MediaStore columns read for one image. Kept to columns present on every API 26+. */
        val MEDIA_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )

        val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES
        )

        val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = resolveProjection(projection, ROOT_PROJECTION)
        val cursor = MatrixCursor(columns)
        val appContext = context ?: return cursor
        // Stay out of the picker's drawer until the user has actually granted media access. An empty
        // root would otherwise be offered by every picker on the device and then lead nowhere.
        if (!canReadImages(appContext)) return cursor
        cursor.addRow(
            columns,
            mapOf(
                Root.COLUMN_ROOT_ID to ROOT_ID,
                Root.COLUMN_DOCUMENT_ID to ROOT_ID,
                Root.COLUMN_TITLE to appContext.getString(R.string.app_name),
                Root.COLUMN_ICON to R.mipmap.ic_launcher,
                Root.COLUMN_FLAGS to Root.FLAG_LOCAL_ONLY,
                Root.COLUMN_MIME_TYPES to MIME_IMAGE_ALL
            )
        )
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = resolveProjection(projection, DOCUMENT_PROJECTION)
        val cursor = MatrixCursor(columns)
        val appContext = context ?: return cursor
        if (documentId == ROOT_ID) {
            cursor.addRow(columns, rootDocument(appContext))
            return cursor
        }
        val mediaId = documentId.toLongOrNull() ?: return cursor
        readDocument(appContext, mediaId)?.let { cursor.addRow(columns, it) }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = resolveProjection(projection, DOCUMENT_PROJECTION)
        val cursor = MatrixCursor(columns)
        val appContext = context ?: return cursor
        if (parentDocumentId != ROOT_ID) return cursor
        appContext.contentResolver
            .query(imagesCollection(), MEDIA_PROJECTION, null, null, NEWEST_FIRST)
            ?.use { media ->
                while (media.moveToNext()) {
                    cursor.addRow(columns, documentValues(media))
                }
            }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Pixa documents are read-only")
        val appContext = context ?: throw FileNotFoundException("Provider is not attached")
        val mediaId = documentId.toLongOrNull() ?: throw FileNotFoundException("Not a document: $documentId")
        val source = ContentUris.withAppendedId(imagesCollection(), mediaId)
        return appContext.contentResolver.openFileDescriptor(source, mode, signal)
            ?: throw FileNotFoundException("No file for document: $documentId")
    }

    /**
     * The picker's grid reads a thumbnail per cell through here. Anything that goes wrong — an
     * undecodable format, a cancelled decode, a full cache — leaves as [FileNotFoundException], which
     * is the one failure the picker renders as a plain format icon rather than a broken cell.
     */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val appContext = context ?: throw FileNotFoundException("Provider is not attached")
        val mediaId = documentId.toLongOrNull() ?: throw FileNotFoundException("Not a document: $documentId")
        val thumbnail = ensureThumbnail(appContext, mediaId, clampedEdge(sizeHint), signal)
        val descriptor = ParcelFileDescriptor.open(thumbnail, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, thumbnail.length())
    }

    private fun rootDocument(appContext: Context): Map<String, Any?> = mapOf(
        Document.COLUMN_DOCUMENT_ID to ROOT_ID,
        Document.COLUMN_MIME_TYPE to Document.MIME_TYPE_DIR,
        Document.COLUMN_DISPLAY_NAME to appContext.getString(R.string.app_name),
        Document.COLUMN_SIZE to 0L,
        Document.COLUMN_LAST_MODIFIED to 0L,
        Document.COLUMN_FLAGS to 0
    )

    private fun readDocument(appContext: Context, mediaId: Long): Map<String, Any?>? {
        appContext.contentResolver.query(
            imagesCollection(),
            MEDIA_PROJECTION,
            "${MediaStore.Images.Media._ID} = ?",
            arrayOf(mediaId.toString()),
            null
        )?.use { media ->
            if (media.moveToFirst()) return documentValues(media)
        }
        return null
    }

    /** One MediaStore row as the document columns the picker reads. */
    private fun documentValues(media: Cursor): Map<String, Any?> {
        val id = media.getLong(media.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        val name = media.getString(media.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
        val mime = media.getString(media.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))
            ?: name?.let(MediaFormats::mimeFor)
            ?: FALLBACK_IMAGE_MIME
        val size = media.getLong(media.getColumnIndex(MediaStore.Images.Media.SIZE))
        val addedSeconds = media.getLong(media.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
        return mapOf(
            Document.COLUMN_DOCUMENT_ID to id.toString(),
            Document.COLUMN_MIME_TYPE to mime,
            Document.COLUMN_DISPLAY_NAME to name,
            Document.COLUMN_SIZE to size,
            Document.COLUMN_LAST_MODIFIED to addedSeconds * SECONDS_TO_MILLIS,
            Document.COLUMN_FLAGS to Document.FLAG_SUPPORTS_THUMBNAIL
        )
    }

    /** The cached thumbnail for this image and edge, generating it first when it is not there yet. */
    private fun ensureThumbnail(
        appContext: Context,
        mediaId: Long,
        edge: Int,
        signal: CancellationSignal?
    ): File {
        val directory = File(appContext.cacheDir, THUMB_DIR).apply { mkdirs() }
        val target = File(directory, "$mediaId-$edge.jpg")
        if (target.length() > 0) return target
        val bitmap = decodeThumbnail(appContext, mediaId, edge, signal)
            ?: throw FileNotFoundException("No thumbnail for image $mediaId")
        return writeThumbnail(bitmap, target)
    }

    /**
     * API 29+ hands decoding to the media stack, which also applies the EXIF rotation and knows the
     * formats the platform ships. Below that there is no such call, so the bytes are sampled down by
     * hand and rotated with what [ExifInterface] reports.
     */
    private fun decodeThumbnail(
        appContext: Context,
        mediaId: Long,
        edge: Int,
        signal: CancellationSignal?
    ): Bitmap? {
        signal?.throwIfCanceled()
        val source = ContentUris.withAppendedId(imagesCollection(), mediaId)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.contentResolver.loadThumbnail(source, Size(edge, edge), signal)
            } else {
                decodeDownscaled(appContext, source, edge, signal)
            }
        }.getOrNull()
    }

    private fun decodeDownscaled(
        appContext: Context,
        source: Uri,
        edge: Int,
        signal: CancellationSignal?
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        signal?.throwIfCanceled()
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, edge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = appContext.contentResolver.openInputStream(source)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        return applyExifOrientation(appContext, source, decoded)
    }

    /** Power-of-two sample so neither edge of the decoded bitmap exceeds [edge]. */
    private fun sampleSizeFor(width: Int, height: Int, edge: Int): Int {
        var sample = 1
        while (width / sample > edge || height / sample > edge) sample *= 2
        return sample
    }

    private fun applyExifOrientation(appContext: Context, source: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            appContext.contentResolver.openInputStream(source)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    /**
     * Publishes the encoding through a temp file and a rename, so two concurrent requests for the same
     * cell (or a reader racing the writer) can never observe a half-written image.
     */
    private fun writeThumbnail(bitmap: Bitmap, target: File): File {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
            if (!temp.renameTo(target) && !target.exists()) {
                throw IOException("Could not publish ${target.name}")
            }
            return target
        } catch (e: IOException) {
            temp.delete()
            // A picker renders FileNotFoundException as its plain format icon; the real cause rides
            // along instead of being dropped.
            throw FileNotFoundException("Could not write thumbnail ${target.name}").apply { initCause(e) }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Whether the app can see the user's images right now. Deliberately not
     * [StoragePermissions.hasMediaAccess], which also demands ACCESS_MEDIA_LOCATION — a permission a
     * plain image pick has no use for.
     */
    private fun canReadImages(appContext: Context): Boolean {
        val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (granted(appContext, readPermission)) return true
        // Android 14 "Select photos": full access denied, only the user-picked subset is readable.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(appContext, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun granted(appContext: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun clampedEdge(sizeHint: Point): Int =
        maxOf(sizeHint.x, sizeHint.y).coerceIn(THUMB_MIN_EDGE, THUMB_MAX_EDGE)

    /** The whole external volume on API 29+; the single external volume below it. */
    private fun imagesCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun resolveProjection(requested: Array<out String>?, fallback: Array<String>): Array<String> =
        if (requested.isNullOrEmpty()) fallback else Array(requested.size) { requested[it] }

    /**
     * Adds one row holding exactly the requested columns, so a caller that asks for a subset (or an
     * unfamiliar OEM picker that asks for extra columns) never trips the cursor.
     */
    private fun MatrixCursor.addRow(columns: Array<String>, values: Map<String, Any?>) {
        val row = newRow()
        for (column in columns) {
            row.add(column, values[column])
        }
    }
}
