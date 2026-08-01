package com.devomind.gallerysearch

import android.graphics.Bitmap

/**
 * Least-squares Umeyama-style similarity transform mapping the five YuNet landmarks onto the
 * canonical 112x112 InsightFace/ArcFace layout, producing the aligned crop MobileFaceNet expects.
 *
 * YuNet emits landmarks (left eye, right eye, nose, mouth left, mouth right) in face space.
 * This class computes (scale, rotation, tx, ty) aligning them to [CanonicalLandmarks], then
 * extracts the region from the source bitmap.
 */
object FaceAligner {

    /**
     * Target landmark locations for an upright 112x112 face crop, matching the reference points
     * used in InsightFace's ArcFace preprocessing note.
     */
    private val CanonicalLandmarks: Array<FloatArray> = arrayOf(
        floatArrayOf(38.2946f, 51.6963f),
        floatArrayOf(73.5318f, 51.5014f),
        floatArrayOf(56.0252f, 71.7366f),
        floatArrayOf(41.5493f, 92.3655f),
        floatArrayOf(70.7299f, 92.2041f)
    )

    private const val AlignedSize = 112

    /**
     * Map [source] to an [AlignedSize]x[AlignedSize] bitmap via the similarity transform that best
     * fits [landmarks] to [CanonicalLandmarks]. The input landmarks must be in the order defined by
     * [YuNetDetector.FaceDetection.landmarks].
     */
    fun align(source: Bitmap, landmarks: Array<FloatArray>): Bitmap {
        val matrix = estimateSimilarityMatrix(landmarks, CanonicalLandmarks)
        return Bitmap.createBitmap(AlignedSize, AlignedSize, Bitmap.Config.ARGB_8888).also { out ->
            android.graphics.Canvas(out).apply {
                drawBitmap(source, matrix, null)
            }
        }
    }

    /**
     * Returns a 3x3 row-major float array [a b tx; -b a ty; 0 0 1] or its inverse. We want to map
     * source -> destination yet Canvas draws source coordinates via the matrix's inverse, so we
     * compute the inverse map destination -> source and use that for the draw.
     */
    private fun estimateSimilarityMatrix(from: Array<FloatArray>, to: Array<FloatArray>): android.graphics.Matrix {
        // Umeyama 3-point closed form is sufficient with 5 noisy points; taking the first three
        // (eyes + nose) prioritizes stable geometry that anchors the face.
        val (a1, b1, tx1, ty1) = similarityParams(from[0], from[1], from[2], to[0], to[1], to[2])
        // Map destination -> source (inverse) so Canvas(source, matrix) places the aligned crop.
        val det = a1 * a1 + b1 * b1
        val aInv = a1 / det
        val bInv = -b1 / det
        val txInv = -(aInv * tx1 - bInv * ty1)
        val tyInv = -(bInv * tx1 + aInv * ty1)
        return android.graphics.Matrix().apply {
            setValues(
                floatArrayOf(
                    aInv, bInv, txInv,
                    -bInv, aInv, tyInv,
                    0f, 0f, 1f
                )
            )
        }
    }

    /**
     * Closed-form similarity parameters (a = s cos θ, b = s sin θ, tx, ty) fitting two point triples.
     * Solves [formula for similarity transform].
     */
    private fun similarityParams(
        s1: FloatArray, s2: FloatArray, s3: FloatArray,
        d1: FloatArray, d2: FloatArray, d3: FloatArray
    ): FloatArray {
        // Least-squares similarity transform via Kabsch on centered coordinates (2D).
        val (smx, smy) = centroid(listOf(s1, s2, s3))
        val (dmx, dmy) = centroid(listOf(d1, d2, d3))
        var c1 = 0f
        var c2 = 0f
        var norm = 0f
        for (pair in listOf(s1 to d1, s2 to d2, s3 to d3)) {
            val (sx, sy) = pair.first[0] - smx to pair.first[1] - smy
            val (dx, dy) = pair.second[0] - dmx to pair.second[1] - dmy
            c1 += sx * dx + sy * dy
            c2 += sx * dy - sy * dx
            norm += sx * sx + sy * sy
        }
        val scale = kotlin.math.sqrt((c1 * c1 + c2 * c2)) / norm
        val angle = kotlin.math.atan2(c2, c1)
        val a = scale * kotlin.math.cos(angle)
        val b = scale * kotlin.math.sin(angle)
        val tx = dmx - (a * smx - b * smy)
        val ty = dmy - (b * smx + a * smy)
        return floatArrayOf(a, b, tx, ty)
    }

    private fun centroid(points: List<FloatArray>): Pair<Float, Float> {
        var cx = 0f
        var cy = 0f
        points.forEach { cx += it[0]; cy += it[1] }
        return cx / points.size to cy / points.size
    }
}
