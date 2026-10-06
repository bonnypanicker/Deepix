package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BatchEncodingTest {

    private val inputs = listOf(floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f))

    @Test
    fun aHealthyBatchIsReturnedAsIs() {
        val vectors = BatchEncoding.encode(
            inputs = inputs,
            encodeBatch = { listOf(floatArrayOf(10f), floatArrayOf(20f), floatArrayOf(30f)) },
            encodeOne = { error("must not be used") },
            onBatchFailure = { error("must not be used") },
            onImageFailure = { _, _ -> error("must not be used") }
        )
        assertEquals(listOf(10f, 20f, 30f), vectors.map { it!![0] })
    }

    /** The finding this exists for: an OOM must reach IndexWorker, not be spent as single-image retries. */
    @Test
    fun outOfMemoryFromTheBatchEscapesWithoutAnyFallbackAttempt() {
        var fallbackRuns = 0
        try {
            BatchEncoding.encode(
                inputs = inputs,
                encodeBatch = { throw OutOfMemoryError("batch too big") },
                encodeOne = { fallbackRuns++; floatArrayOf(0f) },
                onBatchFailure = { },
                onImageFailure = { _, _ -> }
            )
            fail("the OutOfMemoryError should have escaped")
        } catch (expected: OutOfMemoryError) {
            assertEquals(0, fallbackRuns)
        }
    }

    @Test
    fun aBrokenBatchFallsBackToPerImageAndKeepsEverySlot() {
        val failures = mutableListOf<Int>()
        val vectors = BatchEncoding.encode(
            inputs = inputs,
            encodeBatch = { throw IllegalStateException("session died") },
            encodeOne = { floats ->
                if (floats[0] == 2f) throw IllegalStateException("bad image") else floatArrayOf(floats[0] * 10)
            },
            onBatchFailure = { },
            onImageFailure = { index, _ -> failures.add(index) }
        )
        assertEquals(3, vectors.size)
        assertEquals(10f, vectors[0]!![0])
        assertNull(vectors[1])
        assertEquals(30f, vectors[2]!![0])
        assertEquals(listOf(1), failures)
    }

    /** An OOM on the fallback path is still the heap saying no, not this image's fault. */
    @Test
    fun outOfMemoryDuringFallbackEscapesToo() {
        var encoded = 0
        try {
            BatchEncoding.encode(
                inputs = inputs,
                encodeBatch = { throw IllegalStateException("batch failed") },
                encodeOne = { floats ->
                    if (floats[0] == 2f) throw OutOfMemoryError("single image too big")
                    encoded++
                    floatArrayOf(0f)
                },
                onBatchFailure = { },
                onImageFailure = { _, _ -> }
            )
            fail("the OutOfMemoryError should have escaped")
        } catch (expected: OutOfMemoryError) {
            assertEquals(1, encoded)
        }
    }
}
