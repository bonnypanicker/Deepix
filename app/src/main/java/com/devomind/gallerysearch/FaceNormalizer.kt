package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Normalizes a five-point-aligned 112×112 crop for InsightFace MobileFaceNet (w600k_mbf /
 * ArcFace): NCHW [1, 3, 112, 112], RGB channel order, and `(pixel / 255 - 0.5) / 0.5` so values
 * land in [-1, 1]. This contract is model-specific and must not be reused for another recognizer.
 */
object FaceNormalizer {

    const val InputSize = 112

    /** Preprocess: 112×112 bitmap → NCHW RGB float array in [-1, 1]. */
    fun toTensor(aligned: Bitmap): FloatArray {
        check(aligned.width == InputSize && aligned.height == InputSize) {
            "FaceNormalizer expects ${InputSize}x${InputSize} bitmap, got ${aligned.width}x${aligned.height}"
        }
        val pixels = IntArray(InputSize * InputSize)
        aligned.getPixels(pixels, 0, InputSize, 0, 0, InputSize, InputSize)
        val plane = InputSize * InputSize
        return FloatArray(plane * 3).also { tensor ->
            pixels.forEachIndexed { index, color ->
                // InsightFace's ArcFace ONNX preprocessing uses OpenCV's swapRB=true.
                // Android Bitmaps are ARGB, so the model must receive R, G, B planes here.
                tensor[index] = normalizeChannel(Color.red(color))
                tensor[plane + index] = normalizeChannel(Color.green(color))
                tensor[plane * 2 + index] = normalizeChannel(Color.blue(color))
            }
        }
    }

    private inline fun normalizeChannel(value: Int): Float = (value / 255f - 0.5f) / 0.5f
}
