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

/** Minimal ONNX Runtime implementation of OpenCV Zoo's YuNet face detector. */
class YuNetDetector(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        session = environment.createSession(
            AssetUtils.readAssetBytes(context, ModelAsset),
            OnnxSessionOptions.create(Tag, ThreadCount)
        )
        inputName = session.inputNames.firstOrNull() ?: error("YuNet model has no input")
        Log.d(Tag, "YuNet inputs=${session.inputNames}, outputs=${session.outputNames}")
    }

    fun detectFaceCount(bitmap: Bitmap): Int {
        val resized = if (bitmap.width == InputSize && bitmap.height == InputSize) bitmap
        else Bitmap.createScaledBitmap(bitmap, InputSize, InputSize, true)
        return try {
            val input = toBgrTensor(resized)
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), InputShape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val detections = Strides.flatMap { stride ->
                        decodeStride(
                            stride = stride,
                            cls = output(result, "cls_$stride"),
                            obj = output(result, "obj_$stride"),
                            bbox = output(result, "bbox_$stride")
                        )
                    }
                    nonMaximumSuppress(detections).size
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun output(result: OrtSession.Result, name: String): FloatArray =
        result.get(name).orElseThrow { IllegalStateException("YuNet did not return '$name'; outputs=${session.outputNames}") }
            .value
            .let(OnnxOutput::flattenFloatArray)

    private fun decodeStride(stride: Int, cls: FloatArray, obj: FloatArray, bbox: FloatArray): List<Detection> {
        val cells = minOf(cls.size, obj.size, bbox.size / 4)
        val columns = InputSize / stride
        return buildList {
            for (index in 0 until cells) {
                val confidence = cls[index] * obj[index]
                if (confidence < ConfidenceThreshold) continue
                val x = index % columns
                val y = index / columns
                val offset = index * 4
                val width = exp(bbox[offset + 2].toDouble()).toFloat() * stride
                val height = exp(bbox[offset + 3].toDouble()).toFloat() * stride
                if (width < MinFaceSize || height < MinFaceSize) continue
                val centerX = (x + bbox[offset]) * stride
                val centerY = (y + bbox[offset + 1]) * stride
                add(Detection(centerX - width / 2f, centerY - height / 2f, width, height, confidence))
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

    private fun toBgrTensor(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(InputSize * InputSize)
        bitmap.getPixels(pixels, 0, InputSize, 0, 0, InputSize, InputSize)
        val plane = InputSize * InputSize
        return FloatArray(plane * 3).also { tensor ->
            pixels.forEachIndexed { index, color ->
                tensor[index] = Color.blue(color).toFloat()
                tensor[plane + index] = Color.green(color).toFloat()
                tensor[plane * 2 + index] = Color.red(color).toFloat()
            }
        }
    }

    override fun close() = session.close()

    private data class Detection(val left: Float, val top: Float, val width: Float, val height: Float, val confidence: Float)

    private companion object {
        const val Tag = "YuNetDetector"
        const val ModelAsset = "face_detection_yunet_2023mar.onnx"
        const val InputSize = 320
        const val ThreadCount = 2
        const val ConfidenceThreshold = 0.85f
        const val NmsThreshold = 0.3f
        const val MinFaceSize = 10f
        const val MaxDetections = 64
        val InputShape = longArrayOf(1, 3, InputSize.toLong(), InputSize.toLong())
        val Strides = intArrayOf(8, 16, 32)
    }
}
