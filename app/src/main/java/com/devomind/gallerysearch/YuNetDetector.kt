package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Minimal ONNX Runtime implementation of OpenCV Zoo's YuNet face detector. */
class YuNetDetector(
    context: Context,
    /** Confidence floor for accepting a detection. Caller-tunable because the existing
     * CLIP-gated scan pipeline in FaceScanWorker is tuned with a stricter 0.85 default. */
    private val confidenceThreshold: Float = ConfidenceThreshold,
    /** Minimum face side (px) on the detector's internal scale. Smaller values increase recall
     * for tiny/blurry faces at the cost of more false positives inside texture. */
    private val minFaceSize: Float = MinFaceSize,
) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val model = AssetUtils.readAssetBytes(context, ModelAsset)
        // A Git-LFS pointer masquerading as the model once made detection silently return 0 faces.
        check(!BuildConfig.DEBUG || model.size >= MinModelBytes) {
            "$ModelAsset is only ${model.size} bytes; expected a real ONNX binary (>= $MinModelBytes)"
        }
        session = environment.createSession(model, OnnxSessionOptions.create(Tag, ThreadCount))
        inputName = session.inputNames.firstOrNull() ?: error("YuNet model has no input")
        Log.d(Tag, "YuNet inputs=${session.inputNames}, outputs=${session.outputNames}")
    }

    fun detectFaceCount(bitmap: Bitmap): Int = detectFaces(bitmap).size

    /**
     * Full decode: bounding box, confidence, and the five semantic landmarks YuNet exposes
     * (left eye, right eye, nose, left mouth corner, right mouth corner). Coordinates are in
     * the space of the caller-supplied [bitmap]. Returns at most [MaxDetections] results,
     * post-NMS, ordered by confidence.
     */
    fun detectFaces(bitmap: Bitmap): List<FaceDetection> = detectOnce(bitmap)

    /** Single detection pass at the current input resolution cap. */
    private fun detectOnce(bitmap: Bitmap): List<FaceDetection> {
        val (width, height) = targetSize(bitmap.width, bitmap.height)
        val resized = if (bitmap.width == width && bitmap.height == height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
        val scaleX = bitmap.width.toFloat() / width
        val scaleY = bitmap.height.toFloat() / height
        return try {
            val input = toBgrTensor(resized, width, height)
            val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val detections = Strides.flatMap { stride ->
                        decodeStride(
                            stride = stride,
                            columns = width / stride,
                            rows = height / stride,
                            cls = output(result, "cls_$stride"),
                            obj = output(result, "obj_$stride"),
                            bbox = output(result, "bbox_$stride"),
                            kps = output(result, "kps_$stride")
                        )
                    }
                    nonMaximumSuppress(detections).map { d ->
                        FaceDetection(
                            left = (d.left * scaleX).coerceIn(0f, bitmap.width.toFloat()),
                            top = (d.top * scaleY).coerceIn(0f, bitmap.height.toFloat()),
                            width = d.width * scaleX,
                            height = d.height * scaleY,
                            confidence = d.confidence,
                            landmarks = Array(d.landmarks.size) { i ->
                                floatArrayOf(d.landmarks[i][0] * scaleX, d.landmarks[i][1] * scaleY)
                            }
                        )
                    }
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    /** A single YuNet detection, in pixel coordinates of the source bitmap. */
    data class FaceDetection(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val confidence: Float,
        /** Five points in order: left eye, right eye, nose, left mouth corner, right mouth corner. */
        val landmarks: Array<FloatArray>
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        override fun equals(other: Any?): Boolean =
            other is FaceDetection && left == other.left && top == other.top &&
                width == other.width && height == other.height && confidence == other.confidence &&
                landmarks.contentDeepEquals(other.landmarks)
        override fun hashCode(): Int =
            arrayOf(left, top, width, height, confidence, landmarks.contentDeepHashCode()).contentHashCode()
    }

    /**
     * Aspect-preserving fit into [MaxLongEdge], snapped to the nearest multiple of the largest
     * stride. Never upscales: interpolated pixels carry no new detail for the detector, so a
     * source already within the cap runs at (stride-snapped) native size.
     */
    private fun targetSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val scale = min(1f, MaxLongEdge.toFloat() / max(sourceWidth, sourceHeight))
        val width = snapToStride((sourceWidth * scale).roundToInt())
        val height = snapToStride((sourceHeight * scale).roundToInt())
        return width to height
    }

    /** Nearest multiple of [StrideAlignment]; rounding (vs. flooring) halves the worst-case
     * aspect distortion, which otherwise skews the landmarks fed to alignment. */
    private fun snapToStride(value: Int): Int =
        max(StrideAlignment, (value + StrideAlignment / 2) / StrideAlignment * StrideAlignment)

    private fun output(result: OrtSession.Result, name: String): FloatArray =
        result.get(name).orElseThrow { IllegalStateException("YuNet did not return '$name'; outputs=${session.outputNames}") }
            .value
            .let(OnnxOutput::flattenFloatArray)

    private fun decodeStride(
        stride: Int,
        columns: Int,
        rows: Int,
        cls: FloatArray,
        obj: FloatArray,
        bbox: FloatArray,
        kps: FloatArray
    ): List<Detection> {
        val cells = minOf(cls.size, obj.size, bbox.size / 4, kps.size / 10, columns * rows)
        // Diagnostic: capture the raw ranges seen in cls/obj so we can verify the export semantics
        // (sigmoided [0,1] probabilities vs. raw logits) and detect where NaN enters.
        if (BuildConfig.DEBUG) {
            var clsMin = Float.POSITIVE_INFINITY
            var clsMax = Float.NEGATIVE_INFINITY
            var objMin = Float.POSITIVE_INFINITY
            var objMax = Float.NEGATIVE_INFINITY
            var clsNaN = 0
            var objNaN = 0
            for (i in 0 until cells) {
                val c = cls[i]
                val o = obj[i]
                if (c.isNaN()) clsNaN++ else { clsMin = minOf(clsMin, c); clsMax = maxOf(clsMax, c) }
                if (o.isNaN()) objNaN++ else { objMin = minOf(objMin, o); objMax = maxOf(objMax, o) }
            }
            Log.d(
                Tag,
                "stride=$stride cells=$cells cls:[$clsMin..$clsMax](NaN=$clsNaN) obj:[$objMin..$objMax](NaN=$objNaN) " +
                        "sample={${cls.slice(0 until minOf(5, cells)).joinToString(", ")}} / " +
                        "{${obj.slice(0 until minOf(5, cells)).joinToString(", ")}}"
            )
        }
        var nanRejections = 0
        var confidenceFloorRejections = 0
        // In DEBUG builds, capture the top 10 highest-confidence cells along with their topologies
        // regardless of the active threshold — this lets you see whether the model itself has
        // plausible face candidates that the operating threshold is cutting out.
        val best = if (BuildConfig.DEBUG) {
            java.util.PriorityQueue<Pair<Int, Float>>(10, compareBy { it.second }).apply {
                for (index in 0 until cells) {
                    val c = sqrt((cls[index] * obj[index]).coerceAtLeast(0f))
                    if (!c.isNaN()) {
                        offer(index to c)
                        if (size > 10) poll()
                    }
                }
            }.toList().sortedByDescending { it.second }
        } else emptyList()
        if (BuildConfig.DEBUG && best.isNotEmpty()) {
            Log.d(Tag, "stride=$stride top10: " + best.joinToString { "(idx=${it.first},c=%.3f)".format(it.second) })
        }

        return buildList {
            for (index in 0 until cells) {
                val clsScore = cls[index]
                val objScore = obj[index]
                val product = clsScore * objScore
                // Clamp small negative zero values: sqrt(-0.0) is NaN, and cls/obj are sigmoided
                // probabilities which should never be meaningfully negative — treat below-zero as 0.
                val confidence = sqrt(product.coerceAtLeast(0f))
                if (confidence.isNaN()) {
                    nanRejections++
                    continue
                }
                if (confidence < confidenceThreshold) {
                    confidenceFloorRejections++
                    continue
                }
                val x = index % columns
                val y = index / columns
                val offset = index * 4
                val width = exp(bbox[offset + 2].toDouble()).toFloat() * stride
                val height = exp(bbox[offset + 3].toDouble()).toFloat() * stride
                if (width < minFaceSize || height < minFaceSize) continue
                val centerX = (x + bbox[offset]) * stride
                val centerY = (y + bbox[offset + 1]) * stride
                val kpOffset = index * 10
                val landmarks = Array(5) { landmarkIndex ->
                    floatArrayOf(
                        (x + kps[kpOffset + landmarkIndex * 2]) * stride,
                        (y + kps[kpOffset + landmarkIndex * 2 + 1]) * stride
                    )
                }
                add(Detection(centerX - width / 2f, centerY - height / 2f, width, height, confidence, landmarks))
            }
        }.also { kept ->
            if (BuildConfig.DEBUG) {
                Log.d(
                    Tag,
                    "stride=$stride kept=${kept.size}  (rejected: $confidenceFloorRejections below conf, $nanRejections NaN)"
                )
            }
        }
    }

    private fun nonMaximumSuppress(candidates: List<Detection>): List<Detection> {
        val pending = candidates.sortedByDescending { it.confidence }.toMutableList()
        val accepted = ArrayList<Detection>()
        while (pending.isNotEmpty() && accepted.size < MaxDetections) {
            val detection = pending.removeAt(0)
            accepted += detection
            pending.removeAll { intersectionOverUnion(detection, it) >= NmsThreshold }
        }
        return accepted
    }

    private fun intersectionOverUnion(first: Detection, second: Detection): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.left + first.width, second.left + second.width)
        val bottom = min(first.top + first.height, second.top + second.height)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        return intersection / (first.width * first.height + second.width * second.height - intersection).coerceAtLeast(1e-6f)
    }

    private fun toBgrTensor(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val plane = width * height
        val pixels = IntArray(plane)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return FloatArray(plane * 3).also { tensor ->
            pixels.forEachIndexed { index, color ->
                tensor[index] = Color.blue(color).toFloat()
                tensor[plane + index] = Color.green(color).toFloat()
                tensor[plane * 2 + index] = Color.red(color).toFloat()
            }
        }
    }

    override fun close() = session.close()

    private data class Detection(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val confidence: Float,
        val landmarks: Array<FloatArray>
    )

    companion object {
        private const val Tag = "YuNetDetector"
        private const val ModelAsset = "face_detection_yunet_2026may.onnx"
        private const val MinModelBytes = 50_000
        /**
         * Long-edge cap for the detector's internal resolution. Pairs with
         * GalleryRepository.FaceDetectionMaxEdge (1536): the decode hands the detector more
         * pixels than this, so inference always downsamples real detail rather than
         * interpolating an undersized decode. Together with [MinFaceSize] this sets the
         * effective minimum face size in the source photo (~3% of the long edge).
         */
        private const val MaxLongEdge = 1280
        private const val StrideAlignment = 32
        private const val ThreadCount = 2
        private const val NmsThreshold = 0.3f
        private const val MaxDetections = 64
        private val Strides = intArrayOf(8, 16, 32)

        /** Spec default: confidence floor for accepting detections. */
        const val ConfidenceThreshold = 0.6f

        /** Spec default: minimum face side (px) on the detector's internal scale. */
        const val MinFaceSize = 40f

        /**
         * Tuned for the existing FaceScanWorker CLIP pre-filter pass — keeps the behaviour the
         * already-shipped pipeline was tuned against, without affecting the Phase 1 default.
         */
        fun forScanWorker(context: Context): YuNetDetector = YuNetDetector(
            context = context,
            confidenceThreshold = 0.85f,
            minFaceSize = 10f
        )
    }
}
