package com.devomind.gallerysearch

import android.graphics.Bitmap

/**
 * Implements the spec Phase 2 "CLIP person-gate": a permissive / recall-biased text-prompt gate
 * deciding whether a photo plausibly contains a person. Calibrated as a cheap prefilter before
 * YuNet: photos that score below [PersonGateThreshold] skip the detector entirely; those scoring
 * in the grey zone [GreyBandLow, GreyBandHigh] run YuNet and their outcome is logged so we can
 * tune the threshold against real libraries.
 *
 * Gate score = max cosine between the photo's CLIP embedding and the "person" prompt pool minus
 * max cosine against the "non-person" pool. Ranges roughly [-1, 1]; >0 means more person-like.
 */
object ClipPersonGate {

    /** Default operating point: tuned roughly for ~95% person-recall in early benchmarks. */
    const val PersonGateThreshold = 0.05f

    /** Between these scores we run YuNet anyway and log both the gate decision AND YuNet's
     * answer — pure tuning telemetry for setting the true launch threshold later. */
    const val GreyBandLow = -0.15f
    const val GreyBandHigh = 0.30f

    private val PersonPrompts = arrayOf(
        "a photo of a person",
        "a person smiling at the camera",
        "a selfie",
        "a group of people",
        "portrait photograph",
        "people talking",
        "family photo",
        "a man or woman standing"
    )

    private val NonPersonPrompts = arrayOf(
        "a landscape with no people",
        "a building exterior",
        "a document or screenshot",
        "an empty room",
        "food photography",
        "an animal close-up",
        "mountains and trees"
    )

    /**
     * Reference embeddings for the prompt pools are computed once per process against the shared
     * TextEncoder, then cached. Cheap: 14 tiny text inferences the first time.
     */
    data class GateVerdict(
        val photoUri: String,
        val personScore: Float,
        val nonPersonScore: Float,
        /** Net gate score (personScore − nonPersonScore). */
        val gateScore: Float,
        /** True when the photo crosses the operating threshold — run YuNet. */
        val hasPerson: Boolean,
        /** True when the outcome is in the grey band and worth logging. */
        val isGreyZone: Boolean
    )

    // @Volatile: FaceIndexWorker runs PipelineConcurrency=2 parallel workers that both call
    // score()/scoreEmbedding(); the cache is populated once under [cacheLock] in
    // ensurePromptCache, then read concurrently here. Without @Volatile a worker could observe
    // the pre-population null and trip the !! below.
    @Volatile private var cachedPerson: List<FloatArray>? = null
    @Volatile private var cachedNonPerson: List<FloatArray>? = null
    private val cacheLock = Any()

    /**
     * Score one photo bitmap against the prompt pools. Encodes the bitmap live via the shared
     * MobileCLIP [imageEncoder] — use this only as a fallback when no stored CLIP embedding is
     * available (the normal Phase 2 path reuses the embedding IndexWorker already persisted via
     * [scoreEmbedding] to avoid a second decode + second CLIP inference per photo).
     */
    fun score(
        imageEncoder: ImageEncoder,
        textEncoder: TextEncoder,
        bitmap: Bitmap,
        photoUri: String
    ): GateVerdict = scoreEmbedding(textEncoder, imageEncoder.encode(bitmap), photoUri)

    /**
     * Score a *precomputed* MobileCLIP image embedding (already L2-normalized, e.g. read from
     * `embedding_index.bin`) against the prompt pools. This is the spec's "reuse existing
     * MobileCLIP-S2 embeddings" path: no bitmap decode, no image-encoder inference — just the
     * 14 cached text-prompt embeddings vs. the stored image embedding.
     */
    fun scoreEmbedding(
        textEncoder: TextEncoder,
        imageEmbedding: FloatArray,
        photoUri: String
    ): GateVerdict {
        ensurePromptCache(textEncoder)
        val personScore = cachedPerson!!.maxOf { cosine(imageEmbedding, it) }
        val nonPersonScore = cachedNonPerson!!.maxOf { cosine(imageEmbedding, it) }
        val gate = personScore - nonPersonScore
        return GateVerdict(
            photoUri = photoUri,
            personScore = personScore,
            nonPersonScore = nonPersonScore,
            gateScore = gate,
            hasPerson = gate >= PersonGateThreshold,
            isGreyZone = gate in GreyBandLow..GreyBandHigh
        )
    }

    private fun ensurePromptCache(textEncoder: TextEncoder) {
        synchronized(cacheLock) {
            if (cachedPerson == null) {
                cachedPerson = PersonPrompts.map { textEncoder.encode(it) }
                cachedNonPerson = NonPersonPrompts.map { textEncoder.encode(it) }
            }
        }
    }

    /** True when the stored prompt cache has been populated (used by tests / cold-start exposure). */
    fun promptCacheWarm(): Boolean = synchronized(cacheLock) { cachedPerson != null }

    /** Cosine similarity on L2-normalized embeddings (inputs are pre-normalized by callers). */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        check(a.size == b.size) { "cosine: shape mismatch ${a.size} vs ${b.size}" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /** Reset the cached prompt embeddings (used on cold start / preflight refresh). */
    fun resetPromptCache() {
        synchronized(cacheLock) {
            cachedPerson = null
            cachedNonPerson = null
        }
    }
}
