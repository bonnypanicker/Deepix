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
 * Runs the current face-embedding ONNX model (OpenCV Zoo SFace 2021dec int8 block-quantized via
 * `face_recognition_sface_2021dec_int8bq.onnx`) on a 112×112 aligned crop.
 *
 * Returns an L2-normalized embedding — cosine similarity is a plain dot product between outputs.
 *
 * Model: Apache 2.0, OpenCV Zoo SFace (see https://github.com/opencv/opencv_zoo). The workspace's int8 conversion
 * contains DequantizeLinear / QuantizeLinear ops around the tensors; the public input/output remain fp32 from the
 * caller's side. Input contract (authoritative: `cv::FaceRecognizerSF::feature`): 1×3×112×112 **RGB** in **[0, 255]**,
 * no mean subtraction / scaling — see [FaceNormalizer]. OpenCV Zoo's published SFace model emits 128-d features, so the
 * rest of the pipeline keys off that width and rejects any unexpected model swap at startup.
 *
 * Session is shared app-wide via [GallerySearchApp.sharedEncoders] — created once, reused freely across the app.
 */
class FaceEmbedder(context: Context, threadCount: Int = OnnxSessionOptions.DefaultThreadCount) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

    /** Embedding vector width, read from the live session's output tensor info. */
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
        val shape = (outputInfo as? TensorInfo)?.getShape()
            ?: error("FaceEmbedder output isn't a numeric tensor")
        embeddingDim = shape.lastOrNull()?.toInt() ?: error("Output shape ends unbounded: ${shape.contentToString()}")
        check(embeddingDim == EmbeddingDim) {
            "$ModelAsset returned $embeddingDim-dim embeddings; expected $EmbeddingDim"
        }
        Log.i(Tag, "SFace session up: input=$inputName output=$outputName dim=$embeddingDim (inputs=${session.inputNames} outputs=${session.outputNames})")
    }

    /**
     * Embed the 112×112 [aligned] face crop. Returns an L2-normalized vector of [embeddingDim] floats.
     */
    fun embed(aligned: Bitmap): FloatArray {
        val tensor = FaceNormalizer.toTensor(aligned)
        val shape = longArrayOf(1, 3, FaceNormalizer.InputSize.toLong(), FaceNormalizer.InputSize.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val raw = result.get(outputName).orElseThrow {
                    IllegalStateException("SFace model did not return '$outputName'")
                }.value.let(OnnxOutput::flattenFloatArray)
                check(raw.size == embeddingDim) {
                    "SFace returned ${raw.size} values; expected $embeddingDim"
                }
                return EmbeddingUtils.l2Normalize(raw)
            }
        }
    }

    companion object {
        const val Tag = "FaceEmbedder"
        const val ModelAsset = "face_recognition_sface_2021dec_int8bq.onnx"
        const val ModelVersion = "sface_2021dec_int8bq_v3"
        /** Output width for the exact OpenCV Zoo SFace 2021dec model used by this app. */
        const val EmbeddingDim = 128
        /** OpenCV Zoo's published cosine decision threshold for SFace. */
        const val MatchThresholdCosine = 0.363f
        const val MinModelBytes = 1_000_000

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            check(a.size == b.size) { "cosine on shape mismatch ${a.size} vs ${b.size}" }
            var dot = 0f
            for (i in 0 until a.size) dot += a[i] * b[i]
            return dot
        }
    }

    override fun close() = session.close()
}
