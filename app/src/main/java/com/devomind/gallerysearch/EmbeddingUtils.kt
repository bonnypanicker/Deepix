package com.devomind.gallerysearch

import kotlin.math.sqrt

object EmbeddingUtils {
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (value in vector) {
            sum += value * value
        }
        val norm = sqrt(sum)
        if (norm < 1e-8f) return vector
        return FloatArray(vector.size) { index -> vector[index] / norm }
    }

    /**
     * Cosine similarity, which is the dot product here: both encoders emit unit-length vectors (the
     * shipped models measure 1.000000), so dividing by the norms would only add work to every pair.
     *
     * Sizes are checked instead of trimmed to the shorter vector. An overlap between a 512-float
     * query and a 256-float photo returns a number that looks like a score but compares unrelated
     * halves of two spaces; a model swap would then degrade every query silently.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        check(a.size == b.size) { "Expected embeddings of equal size, got ${a.size} and ${b.size}" }
        var dot = 0f
        for (index in a.indices) {
            dot += a[index] * b[index]
        }
        return dot
    }
}
