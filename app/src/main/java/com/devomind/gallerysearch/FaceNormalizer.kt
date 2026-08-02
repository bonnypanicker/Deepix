package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Normalize an aligned 112x112 bitmap to the input MobileFaceNet expects:
 * (pixel / 255 - 0.5) / 0.5  →  [-1.0, 1.0], in NCHW [1,3,112,112] float order.
 *
 * This export follows the common InsightFace/OpenCV convention: channel-first BGR, not RGB.
 */
object FaceNormalizer {

    const val InputSize = 112

    /** Preprocess: 112x112 bitmap → NCHW BGR float array in [-1, 1]. */
    fun toTensor(aligned: Bitmap): FloatArray {
        check(aligned.width == InputSize && aligned.height == InputSize) {
            "FaceNormalizer expects ${InputSize}x${InputSize} bitmap, got ${aligned.width}x${aligned.height}"
        }
        val pixels = IntArray(InputSize * InputSize)
        aligned.getPixels(pixels, 0, InputSize, 0, 0, InputSize, InputSize)
        val plane = InputSize * InputSize
        return FloatArray(plane * 3).also { tensor ->
            pixels.forEachIndexed { index, color ->
                tensor[index] = normalizeChannel(Color.blue(color))
                tensor[plane + index] = normalizeChannel(Color.green(color))
                tensor[plane * 2 + index] = normalizeChannel(Color.red(color))
            }
        }
    }

    private inline fun normalizeChannel(value: Int): Float = (value / 255f - 0.5f) / 0.5f
}
