package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image and text vectors have to be the same width to be comparable.
 *
 * [EmbeddingUtils.cosineSimilarity] used to sum the overlap of the two arrays, so a 512-float query
 * against a 256-float photo returned a number in the normal range instead of failing: `[1, 0, 0]`
 * against `[1, 0]` scored 1.0, "identical", on half a vector. Nothing in the shipped models trips it
 * today — both sides are 512 and the index drops rows of the wrong width — but a model swap would
 * have degraded every query with no error to follow.
 */
class EmbeddingSimilarityTest {

    @Test
    fun unitLengthVectorsScoreTheirDotProduct() {
        // Both encoders emit unit-length output, so the dot product is the cosine: identical vectors
        // score 1, orthogonal 0, opposite -1. This is the property that lets the loop skip the norms.
        assertEquals(1f, EmbeddingUtils.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 1e-6f)
        assertEquals(0f, EmbeddingUtils.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(-1f, EmbeddingUtils.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
    }

    @Test
    fun vectorsOfDifferentLengthsFailInsteadOfScoringTheirOverlap() {
        val failure = assertThrows(IllegalStateException::class.java) {
            EmbeddingUtils.cosineSimilarity(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f))
        }
        assertTrue(
            "the message has to name both widths to be diagnosable from a crash log",
            "3" in failure.message.orEmpty() && "2" in failure.message.orEmpty()
        )
    }
}
