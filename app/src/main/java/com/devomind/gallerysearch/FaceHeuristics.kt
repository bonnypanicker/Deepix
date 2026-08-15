package com.devomind.gallerysearch

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Composite quality score (0.0–1.0) for a detected face:
 * - size: face dimension relative to a 48px "decent" floor (on the detector's input scale)
 * - blur: variance-of-Laplacian on the gray-scale aligned crop
 * - confidence: raw detector confidence
 *
 * Deliberately simple — this only drives exemplar selection and low-quality flagging, not filtering.
 */
object FaceQualityScorer {

    private const val SizeFloorPx = 48f
    private const val BlurFloor = 8f
    private const val BlurCeiling = 60f
    private const val ConfFloor = 0.6f

    /** Compute the composite quality for [detection] within [bitmap]. */
    fun score(bitmap: Bitmap, detection: YuNetDetector.FaceDetection): Float {
        val sizeScore = (minOf(detection.width, detection.height) / SizeFloorPx).coerceIn(0f, 1f)
        val blurScore = blurScore(bitmap, detection)
        val confScore = ((detection.confidence - ConfFloor) / (1f - ConfFloor)).coerceIn(0f, 1f)
        // NaN admission (e.g. degenerate bbox / empty variance sample) would otherwise poison the
        // composite and crash Room's NOT NULL bind. Fall back to detector confidence only.
        val composite = sizeScore * 0.45f + blurScore * 0.45f + confScore * 0.10f
        return if (composite.isNaN()) confScore else composite.coerceIn(0f, 1f)
    }

    private fun blurScore(bitmap: Bitmap, d: YuNetDetector.FaceDetection): Float {
        val left = d.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = d.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = d.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = d.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return 0f
        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        val lapVar = varianceOfLaplacian(crop)
        crop.recycle()
        return ((lapVar - BlurFloor) / (BlurCeiling - BlurFloor)).coerceIn(0f, 1f)
    }

    private fun varianceOfLaplacian(gray: Bitmap): Float {
        val w = gray.width
        val h = gray.height
        if (w < 3 || h < 3) return 0f
        val pixels = IntArray(w * h)
        gray.getPixels(pixels, 0, w, 0, 0, w, h)
        // 4-neighbour Laplacian kernel, computed inline over the IntArray.
        val laplacian = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = luminance(pixels[i])
                val n = luminance(pixels[i - w])
                val s = luminance(pixels[i + w])
                val e = luminance(pixels[i + 1])
                val w2 = luminance(pixels[i - 1])
                laplacian[i] = -4f * c + n + s + e + w2
            }
        }
        // Compute variance ignoring borders.
        var sum = 0f
        var sumSq = 0f
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val v = laplacian[y * w + x]
                sum += v
                sumSq += v * v
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSq / count) - mean * mean
        // Clamp tiny CPU/FPU-negative values to 0; guard against NaN & ±Inf.
        return when {
            variance.isNaN() -> 0f
            variance.isInfinite() -> BlurCeiling
            variance < 0f -> 0f
            else -> variance
        }
    }

    private inline fun luminance(pixel: Int): Int {
        val r = pixel shr 16 and 0xFF
        val g = pixel shr 8 and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

/**
 * Cheap geometric pose estimate from the five YuNet landmarks. Sufficient for cover-ranking —
 * deliberately avoids pulling in OpenCV's solvePnP dependency per spec's "coarse" guideline.
 *
 * - yaw: left/right eye offset relative to nose (nose between eyes = frontal).
 * - pitch: vertical position of nose relative to eye-line half-height vs mouth-line half-height.
 * - roll: tilt of the eye line.
 *
 * All values are in degrees, positive yaw/pitch = face turned to its left/down (viewer right/up).
 */
object FacePoseEstimator {

    data class Pose(val yaw: Float, val pitch: Float, val roll: Float)

    /** Estimate head pose from YuNet's 5-point landmarks. */
    fun estimate(detection: YuNetDetector.FaceDetection): Pose {
        val leftEye = detection.landmarks[0]
        val rightEye = detection.landmarks[1]
        val nose = detection.landmarks[2]
        val mouthL = detection.landmarks[3]
        val mouthR = detection.landmarks[4]

        // Roll is the eye-line tilt.
        val eyeDx = rightEye[0] - leftEye[0]
        val eyeDy = rightEye[1] - leftEye[1]
        val roll = Math.toDegrees(atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat()

        // Yaw: how far the nose sits from the eye-line midpoint, normalized by face width.
        val eyeMidX = (leftEye[0] + rightEye[0]) / 2f
        val eyeMidY = (leftEye[1] + rightEye[1]) / 2f
        val noseDx = nose[0] - eyeMidX
        val yaw = Math.toDegrees(atan2(noseDx.toDouble(), detection.width.toDouble() / 2.0)).toFloat()

        // Pitch: nose-height relative to the vertical span between eyes and mouth midpoint.
        val mouthMidY = (mouthL[1] + mouthR[1]) / 2f
        val eyeToNose = nose[1] - eyeMidY
        val eyeToMouth = mouthMidY - eyeMidY
        val pitch = if (eyeToMouth > 1e-3f) {
            val normalized = (eyeToNose / eyeToMouth - 0.5f).coerceIn(-1f, 1f)
            Math.toDegrees(atan2(normalized.toDouble(), 1.0)).toFloat() * 1.2f
        } else 0f

        return Pose(yaw = yaw, pitch = pitch, roll = roll)
    }

    /** Approximate face width/height aspect (square ~1.0). */
    fun aspectRatio(detection: YuNetDetector.FaceDetection): Float =
        if (detection.height > 1e-6f) detection.width / detection.height else 1f
}

/**
 * Decides whether a YuNet detection carries enough visible facial structure for identity matching.
 *
 * Inspired by Ente's clustering policy: retain weak detections for display/debugging, but do not
 * let only clearly unusable or geometrically implausible samples create a Person. This is a
 * last-resort guard, not a frontal-face filter: valid profile and low-light photos still belong
 * in an identity cluster.
 */
object FaceRecognizabilityGate {

    private const val MinDetectorConfidence = 0.65f
    private const val MinFaceSidePx = 48f
    private const val MinQuality = 0.30f
    private const val MinEyeSeparationFraction = 0.12f
    private const val MinMouthSeparationFraction = 0.06f
    private const val MinVerticalFeatureFraction = 0.04f
    private const val MaxNoseOffsetFromEyeMid = 0.95f
    private const val MaxYawDegrees = 40f
    private const val MaxPitchDegrees = 40f

    fun isEligible(
        detection: YuNetDetector.FaceDetection,
        quality: Float,
        pose: FacePoseEstimator.Pose
    ): Boolean {
        if (detection.confidence < MinDetectorConfidence ||
            minOf(detection.width, detection.height) < MinFaceSidePx ||
            quality < MinQuality ||
            abs(pose.yaw) > MaxYawDegrees ||
            abs(pose.pitch) > MaxPitchDegrees
        ) return false

        val landmarks = detection.landmarks
        if (landmarks.size != 5 || landmarks.any { it.size < 2 || !it[0].isFinite() || !it[1].isFinite() }) {
            return false
        }
        val leftEye = landmarks[0]
        val rightEye = landmarks[1]
        val nose = landmarks[2]
        val mouthL = landmarks[3]
        val mouthR = landmarks[4]
        val eyeDistance = distance(leftEye, rightEye)
        val mouthDistance = distance(mouthL, mouthR)
        if (eyeDistance < detection.width * MinEyeSeparationFraction ||
            mouthDistance < detection.width * MinMouthSeparationFraction
        ) return false

        val eyeMidX = (leftEye[0] + rightEye[0]) / 2f
        val eyeMidY = (leftEye[1] + rightEye[1]) / 2f
        val mouthMidY = (mouthL[1] + mouthR[1]) / 2f
        if (abs(nose[0] - eyeMidX) > eyeDistance * MaxNoseOffsetFromEyeMid ||
            nose[1] - eyeMidY < detection.height * MinVerticalFeatureFraction ||
            mouthMidY - nose[1] < detection.height * MinVerticalFeatureFraction
        ) return false

        return true
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }
}
