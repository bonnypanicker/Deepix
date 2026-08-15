package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * Runs InsightFace MobileFaceNet (w600k_mbf / ArcFace) on a five-point-aligned 112×112 crop.
 *
 * The model expects NCHW RGB pixels normalized to [-1, 1] (see [FaceNormalizer]) and produces a
 * 512-D ArcFace feature. Outputs are L2-normalized so cosine similarity is their dot product.
 *
 * The session is shared process-wide by [GallerySearchApp.sharedEncoders].
 */
class FaceEmbedder(context: Context, threadCount: Int = OnnxSessionOptions.DefaultThreadCount) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

    /** Embedding vector width, validated from the live model output tensor. */
    val embeddingDim: Int

    init {
        val model = AssetUtils.readAssetBytes(context, ModelAsset)
        check(!BuildConfig.DEBUG || model.size >= MinModelBytes) {
            "$ModelAsset is only ${model.size} bytes; expected a real ONNX binary (>= $MinModelBytes)"
        }
        session = environment.createSession(model, OnnxSessionOptions.create(Tag, threadCount))
        inputName = session.inputNames.firstOrNull() ?: error("FaceEmbedder model has no input")
        outputName = session.outputNames.firstOrNull() ?: error("FaceEmbedder model has no output")
        val outputInfo = session.outputInfo[outputName]?.info
            ?: error("No output info for $outputName")
        val shape = (outputInfo as? TensorInfo)?.shape
            ?: error("FaceEmbedder output isn't a numeric tensor")
        embeddingDim = shape.lastOrNull()?.toInt()
            ?: error("Output shape ends unbounded: ${shape.contentToString()}")
        check(embeddingDim == EmbeddingDim) {
            "$ModelAsset returned $embeddingDim-dim embeddings; expected $EmbeddingDim"
        }
        Log.i(Tag, "MobileFaceNet session up: input=$inputName output=$outputName dim=$embeddingDim")
    }

    /** Embed an aligned face crop and return a L2-normalized 512-D ArcFace feature. */
    fun embed(aligned: Bitmap): FloatArray {
        val tensor = FaceNormalizer.toTensor(aligned)
        val shape = longArrayOf(1, 3, FaceNormalizer.InputSize.toLong(), FaceNormalizer.InputSize.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val raw = result.get(outputName).orElseThrow {
                    IllegalStateException("MobileFaceNet did not return '$outputName'")
                }.value.let(OnnxOutput::flattenFloatArray)
                check(raw.size == embeddingDim) {
                    "MobileFaceNet returned ${raw.size} values; expected $embeddingDim"
                }
                return EmbeddingUtils.l2Normalize(raw)
            }
        }
    }

    companion object {
        const val Tag = "FaceEmbedder"
        const val ModelAsset = "mobilefacenet_w600k_mbf.onnx"
        // RGB preprocessing is not comparable to the earlier BGR embedding space. Changing this
        // value makes FaceEmbeddingModelMigration discard and rebuild those stale embeddings.
        // Includes the detector-input orientation and recognizability contracts. Advancing the
        // value rebuilds assignments whenever the eligibility policy changes.
        const val ModelVersion = "w600k_mbf_rgb_oriented_recognizability_v7"
        const val EmbeddingDim = 512
        /** Cross-photo same-person label threshold validated in the working ArcFace pipeline. */
        const val MatchThresholdCosine = 0.50f
        const val MinModelBytes = 1_000_000

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            check(a.size == EmbeddingDim && b.size == EmbeddingDim) {
                "Expected $EmbeddingDim-dim embeddings"
            }
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }
    }

    override fun close() = session.close()
}
