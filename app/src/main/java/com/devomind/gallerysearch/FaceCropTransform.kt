package com.devomind.gallerysearch

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest
import java.util.Objects

/**
 * Crops a bitmap to the face bounding box, expanded by [marginFraction] on each side and made
 * square (centered on the face) so it fits a uniform grid tile cleanly. The bbox is in *original*
 * photo pixels; the transform scales it to the decoded bitmap's coordinate space using
 * [origW]/[origH] (the source image dimensions). Glide preserves aspect ratio when decoding, so
 * `bitmap.width / origW == bitmap.height / origH`.
 *
 * Used by the People grid so each person tile shows the actual face, not a full-photo centerCrop
 * that may not even contain the face in frame.
 *
 * [rotationDegrees] is the clockwise quarter-turn the face was accepted in (mid-confidence
 * rotation-retry): the square crop is rotated by it so rotation-rescued faces show upright.
 */
class FaceCropTransform(
    private val bbox: FloatArray,
    private val origW: Int,
    private val origH: Int,
    private val marginFraction: Float = 0.25f,
    private val rotationDegrees: Int = 0
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        if (origW <= 0 || origH <= 0) return toTransform
        val scale = toTransform.width.toFloat() / origW.toFloat()
        val bx = bbox[0] * scale
        val by = bbox[1] * scale
        val bw = bbox[2] * scale
        val bh = bbox[3] * scale
        if (bw <= 0f || bh <= 0f) return toTransform

        val side = maxOf(bw, bh) * (1f + 2f * marginFraction)
        val cx = bx + bw / 2f
        val cy = by + bh / 2f

        var left = (cx - side / 2f).toInt()
        var top = (cy - side / 2f).toInt()
        var cropW = side.toInt()
        var cropH = side.toInt()

        if (left < 0) { cropW += left; left = 0 }
        if (top < 0) { cropH += top; top = 0 }
        if (left + cropW > toTransform.width) cropW = toTransform.width - left
        if (top + cropH > toTransform.height) cropH = toTransform.height - top
        if (cropW <= 0 || cropH <= 0) return toTransform

        val cropped = Bitmap.createBitmap(toTransform, left, top, cropW, cropH)
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return cropped
        val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true).also { rotated ->
            if (rotated !== cropped) cropped.recycle()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceCropTransform) return false
        return bbox.contentEquals(other.bbox) &&
            origW == other.origW &&
            origH == other.origH &&
            marginFraction == other.marginFraction &&
            rotationDegrees == other.rotationDegrees
    }

    override fun hashCode(): Int = Objects.hash(
        bbox.contentHashCode(), origW, origH, marginFraction, rotationDegrees
    )

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID.toByteArray(Charsets.UTF_8))
        val data = "$origW:$origH:$marginFraction:$rotationDegrees:${bbox.joinToString(",")}"
            .toByteArray(Charsets.UTF_8)
        messageDigest.update(data)
    }

    companion object {
        private val ID = "com.devomind.gallerysearch.FaceCropTransform"
    }
}