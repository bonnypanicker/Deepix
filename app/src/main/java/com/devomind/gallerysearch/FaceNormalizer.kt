package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Normalize an aligned 112x112 bitmap to the input the active face-embedding model expects.
 *
 * Different models expect subtly different input ranges:
 *  - MobileFaceNet (InsightFace ArcFace): (pixel / 255 - 0.5) / 0.5 → roughly [-1.0, 1.0]
 *  - SFace (OpenCV Zoo, quantized int8):   (pixel - 127.5) / 127.5 → [-1.0, 1.0]
 *
 * In practice both norms put `pixel=127.5` at zero and span [-1, 1], but their scale is slightly
 * different (because dividing by 127.5 vs 255*0.5 = 127.5 yields slightly different quantization).
 * Quantized models are far less sensitive to tiny normalization deviations than fp32 versions so
 * either choice works at inference; we keep it consistent with the source's published preprocess
 * for now:   x / 127.5 - 1.0, equivalent to (x - 127.5) / 127.5 across the 0–255 range.
 */
object FaceNormalizer {

    const val InputSize = 112

    /** Preprocess: 112x112 bitmap → NCHW BGR float array normalised for SFace. Uses x/127.5 - 1.0. */
    fun toTensor(aligned: Bitmap): FloatArray {
        check(aligned.width == InputSize && aligned.height == InputSize) {
            "FaceNormalizer expects ${InputSize}x${InputSize} bitmap, got ${aligned.width}x${aligned.height}"
        }
        val pixels = IntArray(InputSize * InputSize)
        aligned.getPixels(pixels, 0, InputSize, 0, 0, InputSize, InputSize)
        val plane = InputSize * InputSize
        return FloatArray(plane * 3).also { tensor ->
            pixels.forEachIndexed { index, color ->
                // BGR channel order, average-0.5 range:
                tensor[index]             = normChannel(Color.blue(color))
                tensor[plane + index]     = normChannel(Color.green(color))
                tensor[plane * 2 + index] = normChannel(Color.red(color))
            }
        }
    }

    private inline fun normChannel(value: Int): Float {
        return value / 127.5f - 1f
    }
}

