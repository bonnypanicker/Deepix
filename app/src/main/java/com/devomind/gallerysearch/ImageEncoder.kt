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

class ImageEncoder private constructor(
    private val context: Context,
    modelBytes: ByteArray,
    threadCount: Int
) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String
    private val processorConfig = ProcessorConfig.fromAssets(context)

    /** Guards the shared OrtSession so indexing and live queries never call run() concurrently. */
    private val sessionLock = Any()

    /**
     * Feature length this model emits, learned from the first encode (0 until one has run). Batch
     * output has no shape of its own — it's a flat float block split evenly across the batch — so
     * without a reference length an off-by-one split would pass silently and poison the index.
     */
    @Volatile var embeddingDim: Int = 0
        private set

    init {
        val options = OnnxSessionOptions.create(
            tag = Tag,
            threadCount = threadCount,
            modelFormat = OnnxSessionOptions.ModelFormat.ORT
        )
        session = environment.createSession(modelBytes, options)
        inputName = session.inputNames.first()
        outputName = session.outputNames.first()
        Log.d(Tag, "Vision model inputs: ${session.inputNames}")
        Log.d(Tag, "Vision model outputs: ${session.outputNames}")
        Log.d(Tag, "Vision processor config: $processorConfig")
    }

    /** Encode a single image. Kept for backward compatibility. */
    fun encode(bitmap: Bitmap): FloatArray = encodePrepared(preprocess(bitmap))

    /** Runs a single already-preprocessed image through the model. */
    fun encodePrepared(preprocessed: FloatArray): FloatArray {
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(preprocessed),
            longArrayOf(1, 3, ImageSize.toLong(), ImageSize.toLong())
        ).use { tensor ->
            // Serialize model runs: indexing (encodeBatch) and live queries (encode) share one
            // OrtSession, and concurrent run() calls can fail depending on the execution provider.
            synchronized(sessionLock) {
                session.run(mapOf(inputName to tensor)).use { result ->
                    val value = result.get(outputName).orElseThrow {
                        IllegalStateException("Vision model did not return output '$outputName'")
                    }.value
                    val vector = EmbeddingUtils.l2Normalize(OnnxOutput.flattenFloatArray(value))
                    if (embeddingDim == 0) embeddingDim = vector.size
                    return vector
                }
            }
        }
    }

    /**
     * Encode a batch of images in a single model call.
     * Stacks all preprocessed images into one [N, 3, 256, 256] tensor,
     * reducing per-image framework overhead.
     *
     * Returns one L2-normalized embedding per input bitmap.
     * If a bitmap fails to preprocess, it is skipped (the returned list
     * may be shorter than the input list — callers must zip with original URIs
     * using the bitmapIndices).
     */
    fun encodeBatch(bitmaps: List<Bitmap>): List<FloatArray> = encodeBatchPrepared(bitmaps.map { preprocess(it) })

    /**
     * Same as [encodeBatch] but takes already-preprocessed images, so callers that preprocessed
     * off the inference thread (e.g. a parallel decode pool) skip re-doing that work here.
     */
    fun encodeBatchPrepared(preprocessed: List<FloatArray>): List<FloatArray> {
        if (preprocessed.isEmpty()) return emptyList()
        if (preprocessed.size == 1) return listOf(encodePrepared(preprocessed[0]))

        val batchSize = preprocessed.size
        val planeSize = ImageSize * ImageSize
        val imageFloatCount = 3 * planeSize

        // Stack all preprocessed images into one flat array
        val batchArray = FloatArray(batchSize * imageFloatCount)
        for (i in preprocessed.indices) {
            preprocessed[i].copyInto(batchArray, destinationOffset = i * imageFloatCount)
        }

        val shape = longArrayOf(batchSize.toLong(), 3, ImageSize.toLong(), ImageSize.toLong())
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(batchArray),
            shape
        ).use { tensor ->
            synchronized(sessionLock) {
                session.run(mapOf(inputName to tensor)).use { result ->
                    val value = result.get(outputName).orElseThrow {
                        IllegalStateException("Vision model did not return output '$outputName'")
                    }.value

                    // Output shape is [N, embeddingDim] — extract each row
                    return extractBatchEmbeddings(value, batchSize)
                }
            }
        }
    }

    /**
     * Extracts per-image embeddings from the batched model output.
     * Handles both Array<FloatArray> (rank-2) and flat FloatArray outputs.
     */
    private fun extractBatchEmbeddings(value: Any, batchSize: Int): List<FloatArray> {
        val rows = when (value) {
            // Output is Array<FloatArray> with shape [N, embeddingDim]
            is Array<*> -> (0 until batchSize).map { OnnxOutput.flattenFloatArray(value[it]!!) }
            is FloatArray -> splitFlatOutput(value, batchSize)
            // Fallback: flatten and split
            else -> splitFlatOutput(OnnxOutput.flattenFloatArray(value), batchSize)
        }
        val expected = embeddingDim
        if (expected > 0) {
            rows.firstOrNull { it.size != expected }?.let { row ->
                throw IllegalStateException(
                    "Vision batch row is ${row.size} floats, single encodes are $expected."
                )
            }
        } else if (rows.isNotEmpty()) {
            embeddingDim = rows.first().size
        }
        return rows.map { EmbeddingUtils.l2Normalize(it) }
    }

    /** A flat output that doesn't divide evenly across the batch isn't [N, dim] — failing here lets
     *  the indexer retry per image instead of storing a truncated, mis-split vector. */
    private fun splitFlatOutput(flat: FloatArray, batchSize: Int): List<FloatArray> {
        val dim = flat.size / batchSize
        if (dim <= 0 || flat.size % batchSize != 0) {
            throw IllegalStateException(
                "Vision batch output is ${flat.size} floats for $batchSize images."
            )
        }
        return (0 until batchSize).map { flat.copyOfRange(it * dim, it * dim + dim) }
    }

    internal fun preprocess(bitmap: Bitmap): FloatArray {
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
        private const val VisionModelAsset = "vision_model_fp16.ort"
        const val ImageSize = 256
        private val Mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val Std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        internal fun resolveVisionModelAssetName(context: Context): String {
            val available = context.assets.list("")?.toSet().orEmpty()
            check(VisionModelAsset in available) {
                "Missing required ORT vision model asset: $VisionModelAsset"
            }
            return VisionModelAsset
        }

        /** Reads the vision model asset bytes — pure IO for eager preload paths. */
        fun preloadModelBytes(context: Context): ByteArray =
            AssetUtils.readAssetBytes(context, resolveVisionModelAssetName(context))

        fun create(
            context: Context,
            threadCount: Int = OnnxSessionOptions.DefaultThreadCount,
            preloadedModelBytes: ByteArray? = null
        ): ImageEncoder {
            val modelBytes = preloadedModelBytes ?: preloadModelBytes(context)
            return ImageEncoder(context, modelBytes, threadCount)
        }
    }
}
