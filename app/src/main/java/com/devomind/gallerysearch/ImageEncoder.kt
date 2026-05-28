package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class ImageEncoder(private val context: Context) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String
    private val processorConfig = ProcessorConfig.fromAssets(context)

    init {
        val modelBytes = AssetUtils.readAssetBytes(context, "vision_model_int8.onnx")
        val options = OnnxSessionOptions.create(Tag)
        session = environment.createSession(modelBytes, options)
        inputName = session.inputNames.first()
        outputName = session.outputNames.first()
        Log.d(Tag, "Vision model inputs: ${session.inputNames}")
        Log.d(Tag, "Vision model outputs: ${session.outputNames}")
        Log.d(Tag, "Vision processor config: $processorConfig")
    }

    fun encode(bitmap: Bitmap): FloatArray {
        val image = preprocess(bitmap)
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(image),
            longArrayOf(1, 3, ImageSize.toLong(), ImageSize.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val value = result.get(outputName).orElseThrow {
                    IllegalStateException("Vision model did not return output '$outputName'")
                }.value
                return EmbeddingUtils.l2Normalize(OnnxOutput.flattenFloatArray(value))
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val resized = resizeShortestEdge(bitmap, ImageSize)
        val left = ((resized.width - ImageSize) / 2).coerceAtLeast(0)
        val top = ((resized.height - ImageSize) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(resized, left, top, ImageSize, ImageSize)
        if (resized !== bitmap && resized !== cropped) {
            resized.recycle()
        }

        val pixels = IntArray(ImageSize * ImageSize)
        cropped.getPixels(pixels, 0, ImageSize, 0, 0, ImageSize, ImageSize)
        if (cropped !== bitmap) {
            cropped.recycle()
        }

        val floats = FloatArray(3 * ImageSize * ImageSize)
        val planeSize = ImageSize * ImageSize
        for (index in pixels.indices) {
            val color = pixels[index]
            floats[index] = normalize(Color.red(color) / 255f, 0)
            floats[planeSize + index] = normalize(Color.green(color) / 255f, 1)
            floats[2 * planeSize + index] = normalize(Color.blue(color) / 255f, 2)
        }
        return floats
    }

    private fun normalize(value: Float, channel: Int): Float {
        if (!processorConfig.doNormalize) return value
        return (value - Mean[channel]) / Std[channel]
    }

    private fun resizeShortestEdge(bitmap: Bitmap, target: Int): Bitmap {
        if (bitmap.width == target && bitmap.height == target) return bitmap
        val scale = target.toFloat() / minOf(bitmap.width, bitmap.height).toFloat()
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(target)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(target)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    override fun close() {
        session.close()
    }

    private data class ProcessorConfig(val doNormalize: Boolean) {
        companion object {
            fun fromAssets(context: Context): ProcessorConfig {
                return runCatching {
                    val json = JSONObject(AssetUtils.readAssetText(context, "preprocessor_config.json"))
                    ProcessorConfig(doNormalize = json.optBoolean("do_normalize", true))
                }.getOrDefault(ProcessorConfig(doNormalize = true))
            }
        }
    }

    companion object {
        private const val Tag = "CLIP"
        private const val ImageSize = 256
        private val Mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val Std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }
}
