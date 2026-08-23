package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Decodes just a detected face region from the original image file.  The detector runs on an
 * upright, downsampled bitmap, so its box is first expressed as a normalised upright rectangle
 * and then mapped back through EXIF orientation to the encoded image.  This keeps a small face
 * sharp without decoding a whole camera photo for every People-grid cell.
 *
 * [rotationDegrees] is the clockwise quarter-turn the face was accepted in under the
 * rotation-retry policy (FaceEntity.rotationDegrees): the crop is rotated by it after EXIF
 * orientation so a sideways face comes out upright, matching its embedding frame.
 */
object OriginalFaceCoverDecoder {

    fun decode(
        context: Context,
        photoUri: Uri,
        detectionBox: FloatArray,
        detectionWidth: Int,
        detectionHeight: Int,
        targetEdge: Int,
        rotationDegrees: Int = 0
    ): Bitmap? {
        if (detectionBox.size < 4 || detectionWidth <= 0 || detectionHeight <= 0) return null
        return runCatching {
            val orientation = readOrientation(context, photoUri)
            context.contentResolver.openFileDescriptor(photoUri, "r")?.use { descriptor ->
                val decoder = BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                    ?: return@use null
                try {
                    val uprightRect = expandedFaceRect(detectionBox, detectionWidth, detectionHeight)
                    val rawRect = uprightToRawRect(
                        uprightRect,
                        decoder.width,
                        decoder.height,
                        orientation
                    )
                    val sample = sampleFor(rawRect.width(), rawRect.height(), targetEdge)
                    val bitmap = decoder.decodeRegion(
                        rawRect,
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                    ) ?: return@use null
                    applyDetectionRotation(applyOrientation(bitmap, orientation), rotationDegrees)
                } finally {
                    decoder.recycle()
                }
            }
        }.getOrNull()
    }

    /** Face rectangle in upright normalised coordinates, including enough context for a cover. */
    private fun expandedFaceRect(box: FloatArray, width: Int, height: Int): FloatArray {
        val faceWidth = box[2].coerceAtLeast(1f)
        val faceHeight = box[3].coerceAtLeast(1f)
        val side = maxOf(faceWidth, faceHeight) * (1f + 2f * CoverMargin)
        val centerX = box[0] + faceWidth / 2f
        val centerY = box[1] + faceHeight / 2f
        val left = ((centerX - side / 2f) / width).coerceIn(0f, 1f)
        val top = ((centerY - side / 2f) / height).coerceIn(0f, 1f)
        val right = ((centerX + side / 2f) / width).coerceIn(0f, 1f)
        val bottom = ((centerY + side / 2f) / height).coerceIn(0f, 1f)
        return floatArrayOf(left, top, right, bottom)
    }

    /** Maps all four corners so rotations and mirrored EXIF orientations stay axis-aligned. */
    private fun uprightToRawRect(
        upright: FloatArray,
        rawWidth: Int,
        rawHeight: Int,
        orientation: Int
    ): Rect {
        val corners = arrayOf(
            rawPoint(upright[0], upright[1], orientation),
            rawPoint(upright[2], upright[1], orientation),
            rawPoint(upright[0], upright[3], orientation),
            rawPoint(upright[2], upright[3], orientation)
        )
        val minX = corners.minOf { it[0] }
        val maxX = corners.maxOf { it[0] }
        val minY = corners.minOf { it[1] }
        val maxY = corners.maxOf { it[1] }
        val left = floor(minX * rawWidth).toInt().coerceIn(0, rawWidth - 1)
        val top = floor(minY * rawHeight).toInt().coerceIn(0, rawHeight - 1)
        val right = ceil(maxX * rawWidth).toInt().coerceIn(left + 1, rawWidth)
        val bottom = ceil(maxY * rawHeight).toInt().coerceIn(top + 1, rawHeight)
        return Rect(left, top, right, bottom)
    }

    /** Converts a displayed/upright normalised point to the encoded source orientation. */
    private fun rawPoint(x: Float, y: Float, orientation: Int): FloatArray = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(1f - x, y)
        ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(1f - x, 1f - y)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(x, 1f - y)
        ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(y, x)
        ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(y, 1f - x)
        ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(1f - y, 1f - x)
        ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(1f - y, x)
        else -> floatArrayOf(x, y)
    }

    private fun sampleFor(width: Int, height: Int, targetEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > targetEdge * 2) sample *= 2
        return sample
    }

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
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
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { rotated ->
            if (rotated !== bitmap) bitmap.recycle()
        }
    }

    /** Detection-time quarter-turn: undoes the sideways presentation of rotation-rescued faces. */
    private fun applyDetectionRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { rotated ->
            if (rotated !== bitmap) bitmap.recycle()
        }
    }

    private const val CoverMargin = 0.32f
}
