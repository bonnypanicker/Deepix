package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Runs InsightFace's MobileFaceNet (w600k_mbf, 512-D embeddings) on a 112×112 aligned crop.
 * Returns an L2-normalized embedding — cosine similarity is a plain dot product between two outputs.
 *
 * Model: Apache 2.0, trained on WebFace600K. Input: 1×3×112×112 BGR, pixels in [-1, 1].
 * Session is shared app-wide via [GallerySearchApp.sharedEncoders] — create once, call embed() freely.
 */
class FaceEmbedder(context: Context, threadCount: Int = OnnxSessionOptions.DefaultThreadCount) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val model = AssetUtils.readAssetBytes(context, ModelAsset)
        check(!BuildConfig.DEBUG || model.size >= MinModelBytes) {
            "$ModelAsset is only ${model.size} bytes; expected a real ONNX binary (>= $MinModelBytes)"
        }
        session = environment.createSession(model, OnnxSessionOptions.create(Tag, threadCount))
        inputName = session.inputNames.firstOrNull() ?: error("FaceEmbedder model has no input")
    }

    /**
     * Embed the 112×112 [aligned] face crop. Returns a 512-float L2-normalized vector.
     */
    fun embed(aligned: Bitmap): FloatArray {
        val tensor = FaceNormalizer.toTensor(aligned)
        val shape = longArrayOf(1, 3, FaceNormalizer.InputSize.toLong(), FaceNormalizer.InputSize.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val raw = OnnxOutput.flattenFloatArray(result[0].value)
                return l2Normalize(raw)
            }
        }
    }

    companion object {
        const val Tag = "FaceEmbedder"
        const val ModelAsset = "mobilefacenet_w600k_mbf.onnx"
        const val EmbeddingDim = 512
        const val MinModelBytes = 1_000_000

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            check(a.size == EmbeddingDim && b.size == EmbeddingDim) { "Expected $EmbeddingDim-dim embeddings" }
            var dot = 0f
            for (i in 0 until EmbeddingDim) dot += a[i] * b[i]
            return dot // both inputs are L2-normalized, so dot == cosine similarity
        }

        private fun l2Normalize(vector: FloatArray): FloatArray {
            var sum = 0f
            for (v in vector) sum += v * v
            val norm = sqrt(sum).takeIf { it > 1e-6f } ?: return FloatArray(vector.size)
            return FloatArray(vector.size) { i -> vector[i] / norm }
        }
    }

    override fun close() = session.close()
}
