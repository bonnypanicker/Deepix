package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import kotlin.math.sqrt

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
    private const val MaxLandmarkRmsePx = 6.5f

    /**
     * Map [source] to an [AlignedSize]x[AlignedSize] bitmap via the similarity transform that best
     * fits [landmarks] to [CanonicalLandmarks]. The input landmarks must be in the order defined by
     * [YuNetDetector.FaceDetection.landmarks].
     *
     * `Canvas.drawBitmap(src, matrix)` samples source at matrix⁻¹(dst) per destination pixel, so to
     * land detected landmarks on the canonical 112×112 points we hand over the FORWARD transform
     * (source → destination); Canvas inverts internally. Inverting beforehand would draw the face
     * OFF the output bitmap yielding a blank crop — which then collapses the embedding space.
     */
    fun align(source: Bitmap, landmarks: Array<FloatArray>): Bitmap {
        val matrix = runCatching { forwardSimilarityMatrix(landmarks, CanonicalLandmarks) }.getOrNull()
        if (matrix == null || !passesSanityCheck(matrix, landmarks, CanonicalLandmarks)) {
            Log.w(Tag, "Falling back to landmark-bounds crop for unstable alignment")
            return fallbackAlignedCrop(source, landmarks)
        }
        return Bitmap.createBitmap(AlignedSize, AlignedSize, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).apply {
                drawBitmap(source, matrix, null)
            }
        }
    }

    /**
     * Returns the source→destination similarity matrix mapping [from] onto [to]. Canvas samples
     * source pixels via this matrix's inverse, so the points in [from] land exactly at [to].
     */
    private fun forwardSimilarityMatrix(from: Array<FloatArray>, to: Array<FloatArray>): Matrix {
        require(from.size == to.size && from.size >= 3) {
            "Need matching landmark sets (>=3 points), got ${from.size} and ${to.size}"
        }
        // Similarity fit over all five landmarks. Using only three points makes the warp overly
        // sensitive to one slightly noisy landmark, especially on small or profile faces.
        val (a, b, tx, ty) = similarityParams(from, to)
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    a, -b, tx,
                    b, a, ty,
                    0f, 0f, 1f
                )
            )
        }
    }

    /**
     * Closed-form least-squares similarity parameters (a = s cos θ, b = s sin θ, tx, ty)
     * over a corresponding set of 2D points.
     */
    private fun similarityParams(
        source: Array<FloatArray>,
        destination: Array<FloatArray>
    ): FloatArray {
        val (smx, smy) = centroid(source)
        val (dmx, dmy) = centroid(destination)
        var c1 = 0f
        var c2 = 0f
        var norm = 0f
        for (index in source.indices) {
            val sx = source[index][0] - smx
            val sy = source[index][1] - smy
            val dx = destination[index][0] - dmx
            val dy = destination[index][1] - dmy
            c1 += sx * dx + sy * dy
            c2 += sx * dy - sy * dx
            norm += sx * sx + sy * sy
        }
        require(norm > 1e-6f) { "Degenerate landmark geometry" }
        val a = c1 / norm
        val b = c2 / norm
        val tx = dmx - (a * smx - b * smy)
        val ty = dmy - (b * smx + a * smy)
        return floatArrayOf(a, b, tx, ty)
    }

    private fun passesSanityCheck(
        matrix: Matrix,
        source: Array<FloatArray>,
        destination: Array<FloatArray>
    ): Boolean {
        val sourcePoints = FloatArray(source.size * 2) { index ->
            source[index / 2][index % 2]
        }
        val mapped = sourcePoints.copyOf()
        matrix.mapPoints(mapped)
        var sumSq = 0f
        for (index in source.indices) {
            val dx = mapped[index * 2] - destination[index][0]
            val dy = mapped[index * 2 + 1] - destination[index][1]
            if (!dx.isFinite() || !dy.isFinite()) return false
            sumSq += dx * dx + dy * dy
        }
        val rmse = sqrt(sumSq / source.size)
        if (rmse > MaxLandmarkRmsePx) return false

        val values = FloatArray(9)
        matrix.getValues(values)
        val scale = sqrt(values[Matrix.MSCALE_X] * values[Matrix.MSCALE_X] + values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y])
        return scale.isFinite() && scale in 0.05f..20f
    }

    private fun fallbackAlignedCrop(source: Bitmap, landmarks: Array<FloatArray>): Bitmap {
        require(landmarks.isNotEmpty()) { "No landmarks supplied" }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var sumX = 0f
        var sumY = 0f
        landmarks.forEach { point ->
            minX = minOf(minX, point[0])
            minY = minOf(minY, point[1])
            maxX = maxOf(maxX, point[0])
            maxY = maxOf(maxY, point[1])
            sumX += point[0]
            sumY += point[1]
        }
        val centerX = sumX / landmarks.size
        val centerY = sumY / landmarks.size + (maxY - minY) * 0.12f
        val side = maxOf((maxX - minX) * 2.0f, (maxY - minY) * 2.4f).coerceAtLeast(16f)
        val half = side / 2f
        val left = (centerX - half).toInt().coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val top = (centerY - half).toInt().coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val right = (centerX + half).toInt().coerceIn(left + 1, source.width)
        val bottom = (centerY + half).toInt().coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return Bitmap.createScaledBitmap(crop, AlignedSize, AlignedSize, true).also {
            if (crop !== it) crop.recycle()
        }
    }

    private fun centroid(points: Array<FloatArray>): Pair<Float, Float> {
        var cx = 0f
        var cy = 0f
        points.forEach { cx += it[0]; cy += it[1] }
        return cx / points.size to cy / points.size
    }

    private const val Tag = "FaceAligner"
}
