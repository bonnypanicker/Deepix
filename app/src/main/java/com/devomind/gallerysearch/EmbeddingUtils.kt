package com.devomind.gallerysearch

import kotlin.math.min
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

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val size = min(a.size, b.size)
        var dot = 0f
        for (index in 0 until size) {
            dot += a[index] * b[index]
        }
        return dot
    }
}
