package com.devomind.gallerysearch

/**
 * Zero-shot NSFW / sensitive-content detector built on the existing MobileCLIP encoders.
 *
 * It embeds a small set of "sensitive" and "safe" text prompts once (cached), then scores an
 * already-computed image embedding by comparing its cosine similarity to the closest prompt in each
 * set. An image is flagged sensitive when it is meaningfully closer to a sensitive prompt than to
 * any safe prompt. No image is re-encoded — this reuses the indexed embeddings, so classifying the
 * whole library is just a handful of dot products per photo.
 *
 * This is intentionally conservative and tunable ([SENSITIVE_MARGIN]); it is a Beta heuristic, not a
 * guaranteed filter.
 */
class NsfwClassifier(private val textEncoder: TextEncoder) {

    // Note: ClipTokenizer auto-prepends "a photo of ", so these read as "a photo of <prompt>".
    // Prompt sets + margin were calibrated with tools/nsfw_calibration against the real MobileCLIP-S2
    // ONNX model over 200 safe public photos. A broad, gallery-representative "safe" set is essential:
    // with only a few generic safe prompts, ~50% of normal photos scored as sensitive on this model.
    private val sensitivePrompts = listOf(
        "explicit nude sexual content",
        "pornography",
        "a naked body",
        "sexual intercourse",
        "exposed genitalia"
    )

    private val safePrompts = listOf(
        "a person wearing clothes", "a portrait of a clothed person", "a group of people",
        "a selfie", "a child", "a family photo", "a wedding", "a party or celebration",
        "a landscape", "nature", "a mountain", "the sky", "a beach", "a sunset",
        "food on a plate", "a drink", "an animal", "a pet dog or cat", "a bird",
        "a flower", "a plant", "a tree", "a car or vehicle", "a building", "a street",
        "a room interior", "furniture", "a document", "a screenshot", "text on a screen",
        "a poster or artwork", "clothing and fashion", "shoes", "sports", "a concert",
        "a toy", "electronics or a gadget", "a book", "a map"
    )

    private val sensitiveEmbeddings: List<FloatArray> by lazy { encodeAll(sensitivePrompts) }
    private val safeEmbeddings: List<FloatArray> by lazy { encodeAll(safePrompts) }

    fun isReady(): Boolean = sensitiveEmbeddings.isNotEmpty() && safeEmbeddings.isNotEmpty()

    /** True when [imageEmbedding] (L2-normalized) looks sensitive under the zero-shot comparison. */
    fun isSensitive(imageEmbedding: FloatArray): Boolean {
        if (!isReady()) return false
        val sensitiveScore = sensitiveEmbeddings.maxOf { EmbeddingUtils.cosineSimilarity(imageEmbedding, it) }
        val safeScore = safeEmbeddings.maxOf { EmbeddingUtils.cosineSimilarity(imageEmbedding, it) }
        return sensitiveScore - safeScore >= SENSITIVE_MARGIN
    }

    private fun encodeAll(prompts: List<String>): List<FloatArray> =
        prompts.mapNotNull { runCatching { textEncoder.encode(it) }.getOrNull() }

    private companion object {
        // Calibrated on MobileCLIP-S2 over 200 safe photos (tools/nsfw_calibration): safe photos have
        // median margin -0.012; ~0.5% exceed +0.05, ~0% exceed +0.07. 0.05 favors precision (few
        // false blurs), which is the right trade-off for a blur feature. More aggressive: ~0.04.
        // Note: recall (catching real NSFW) is not validated here — see tools/nsfw_calibration/README.
        const val SENSITIVE_MARGIN = 0.05f
    }
}
