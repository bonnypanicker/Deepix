package com.devomind.gallerysearch

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * 64-bit perceptual difference hash (dHash). Robust to JPEG re-compression, lighting, and small
 * crops; cheap (one pass over an 9x8 downsample). Different from marr-hildreth/pHash (DCT) but
 * sufficient for burst-window duplicate detection on the same photo.
 *
 * Implementation: downsample to 9×8, then for each row compare adjacent pixels. Bit i is 1 when
 * pixel(i) is darker than pixel(i+1). Output is 8 rows × 8 bits = 64 bits.
 *
 * Use [PhashUtils.distance] (Hamming) to decide similarity: near-identical images sit ≤ 8,
 * genuinely different photos sit well above 20.
 */
object PhashUtils {

    private const val SampleWidth = 9    // comparisons per row
    private const val SampleHeight = 8

    /** Compute the 64-bit dHash of [bitmap]. */
    fun hash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, SampleWidth, SampleHeight, /* filter */ true)
        try {
            val pixels = IntArray(SampleWidth * SampleHeight)
            small.getPixels(pixels, 0, SampleWidth, 0, 0, SampleWidth, SampleHeight)
            val grays = pixels.map(::luminance8)
            var hash = 0L
            for (y in 0 until SampleHeight) {
                for (x in 0 until SampleWidth - 1) {
                    val i = y * SampleWidth + x
                    val bit = if (grays[i] < grays[i + 1]) 1L else 0L
                    hash = (hash shl 1) or bit
                }
            }
            return hash
        } finally {
            small.recycle()
        }
    }

    /** Hamming distance between two hashes. Range: 0 (identical) .. 64 (totally different). */
    fun distance(first: Long, second: Long): Int {
        return java.lang.Long.bitCount(first xor second)
    }

    /** True when the two hashes are near-duplicates (tuned threshold for same-burst overlap). */
    fun isNearDuplicate(a: Long, b: Long, threshold: Int = NearDuplicateHammingThreshold): Boolean =
        distance(a, b) <= threshold

    /** Convert luminance of an 0xAARRGGBB int to an 8-bit gray. */
    private fun luminance8(pixel: Int): Int {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        // ITU-R BT.601 luma coefficients
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Default tuning: bursts of the same scene under consistent framing land within Hamming
     * distance 8; genuinely different scenes usually land at 18+. The threshold is adjustable
     * per call site so we can tune against a real library without recompiling.
     */
    const val NearDuplicateHammingThreshold = 8

    /**
     * Guard used by DuplicateGuard: two photos are "in the same burst" iff
     * - their dHash Hamming distance is below [NearDuplicateHammingThreshold],
     * - AND their EXIF/creation timestamps are within [BurstWindowMillis],
     * - AND their pixel dimensions agree within [DimensionDeltaPercent].
     */
    class DimensionMatch(val widthMatch: Boolean, val heightMatch: Boolean) {
        val match: Boolean get() = widthMatch && heightMatch
    }

    fun dimensionsMatch(
        aWidth: Int, aHeight: Int,
        bWidth: Int, bHeight: Int,
        tolerancePercent: Int = DimensionDeltaPercent
    ): DimensionMatch {
        val widthMatch = abs(aWidth - bWidth) <= (tolerancePercent / 100f) * maxOf(aWidth, bWidth)
        val heightMatch = abs(aHeight - bHeight) <= (tolerancePercent / 100f) * maxOf(aHeight, bHeight)
        return DimensionMatch(widthMatch, heightMatch)
    }

    const val BurstWindowMillis = 2_000L
    const val DimensionDeltaPercent = 10
}
