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
class YuNetDetector(context: Context) : AutoCloseable {
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
    fun detectFaces(bitmap: Bitmap): List<FaceDetection> {
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

    /** Aspect-preserving fit into [MaxLongEdge], snapped down to a multiple of the largest stride. */
    private fun targetSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val scale = MaxLongEdge.toFloat() / max(sourceWidth, sourceHeight)
        val width = snapToStride((sourceWidth * scale).roundToInt())
        val height = snapToStride((sourceHeight * scale).roundToInt())
        return width to height
    }

    private fun snapToStride(value: Int): Int = max(StrideAlignment, value / StrideAlignment * StrideAlignment)

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
        return buildList {
            for (index in 0 until cells) {
                val confidence = sqrt(cls[index] * obj[index])
                if (confidence < ConfidenceThreshold) continue
                val x = index % columns
                val y = index / columns
                val offset = index * 4
                val width = exp(bbox[offset + 2].toDouble()).toFloat() * stride
                val height = exp(bbox[offset + 3].toDouble()).toFloat() * stride
                if (width < MinFaceSize || height < MinFaceSize) continue
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

    private companion object {
        const val Tag = "YuNetDetector"
        const val ModelAsset = "face_detection_yunet_2026may.onnx"
        const val MinModelBytes = 50_000
        const val MaxLongEdge = 480
        const val StrideAlignment = 32
        const val ThreadCount = 2
        const val ConfidenceThreshold = 0.85f
        const val NmsThreshold = 0.3f
        const val MinFaceSize = 10f
        const val MaxDetections = 64
        val Strides = intArrayOf(8, 16, 32)
    }
}
