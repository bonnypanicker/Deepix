package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Normalize an aligned 112x112 bitmap to the input OpenCV Zoo SFace expects.
 *
 * Authoritative source: `cv::FaceRecognizerSF::feature` in OpenCV
 * (modules/objdetect/src/face_recognize.cpp), which feeds the aligned crop through
 *   dnn::blobFromImage(img, scalefactor = 1, Size(112,112), mean = Scalar(0,0,0), swapRB = true, crop = false)
 * i.e. **RGB channel order, raw [0, 255] pixel values, no mean subtraction, no scaling**.
 *
 * This is NOT the (x-127.5)/127.5 BGR normalization that the previous MobileFaceNet
 * (InsightFace w600k) export wanted — that contract is model-specific and does not transfer
 * to SFace. Feeding [-1,1] BGR to SFace puts the first conv out of distribution and collapses
 * the embedding space (every face pair scores 0.6–0.98 cosine), which is exactly the failure
 * mode this class exists to produce correctly.
 *
 * Output layout: NCHW [1, 3, 112, 112], plane 0 = R, plane 1 = G, plane 2 = B.
 *
 * See [FaceAligner] for the matching alignment contract (InsightFace 5-pt template) and
 * [FaceEmbedder] for the 128-d feature / 0.363 cosine threshold.
 */
object FaceNormalizer {

    const val InputSize = 112

    /** Preprocess: 112x112 bitmap → NCHW [1,3,112,112] RGB float array in [0, 255]. */
    fun toTensor(aligned: Bitmap): FloatArray {
        check(aligned.width == InputSize && aligned.height == InputSize) {
            "FaceNormalizer expects ${InputSize}x${InputSize} bitmap, got ${aligned.width}x${aligned.height}"
        }
        val pixels = IntArray(InputSize * InputSize)
        aligned.getPixels(pixels, 0, InputSize, 0, 0, InputSize, InputSize)
        val plane = InputSize * InputSize
        return FloatArray(plane * 3).also { tensor ->
            pixels.forEachIndexed { index, color ->
                // RGB channel order, raw [0, 255] — matches cv::FaceRecognizerSF (swapRB=true, no mean/scale).
                tensor[index]             = Color.red(color).toFloat()
                tensor[plane + index]     = Color.green(color).toFloat()
                tensor[plane * 2 + index] = Color.blue(color).toFloat()
            }
        }
    }
}
