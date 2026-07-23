package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Benchmarks different ONNX thread counts to find the optimal setting
 * for the current device. Runs once on first launch, result is cached
 * in SharedPreferences.
 *
 * The benchmark creates a small synthetic image, runs inference with
 * different thread counts, and picks the fastest one.
 */
object ThreadBenchmark {

    private const val Tag = "ThreadBenchmark"
    private const val WarmUpRuns = 3
    private const val MeasureRuns = 5
    private val ThreadCandidates = listOf(1, 2, 4, 6)

    /**
     * Returns the optimal thread count for this device.
     * If already benchmarked, returns the cached result immediately.
     * Otherwise runs the benchmark (takes a few seconds).
     */
    suspend fun getOrBenchmark(context: Context): Int {
        val cached = IndexPreferences.getOptimalThreadCount(context)
        if (cached > 0) {
            Log.d(Tag, "Using cached optimal thread count: $cached")
            return cached
        }

        Log.d(Tag, "Running thread benchmark...")
        val optimal = runBenchmark(context)
        IndexPreferences.saveOptimalThreadCount(context, optimal)
        Log.d(Tag, "Benchmark complete — optimal thread count: $optimal")
        return optimal
    }

    private fun runBenchmark(context: Context): Int {
        val env = OrtEnvironment.getEnvironment()
        // Benchmark the same model asset the encoder will actually run.
        val modelBytes = AssetUtils.readAssetBytes(context, ImageEncoder.resolveVisionModelAssetName(context))
        // Benchmark the real batch shape indexing will run, not batch=1 — thread-count tuning for
        // a single image doesn't necessarily transfer to a batched [N,3,256,256] tensor.
        val batchSize = BatchSizing.computeBatchSize(context)
        val testInput = createSyntheticInput(batchSize)

        var bestThreads = 4 // safe default
        var bestTime = Long.MAX_VALUE

        for (threads in ThreadCandidates) {
            val elapsed = try {
                benchmarkWithThreads(env, modelBytes, testInput, threads, batchSize)
            } catch (e: Exception) {
                Log.w(Tag, "Benchmark failed for threads=$threads", e)
                Long.MAX_VALUE
            }

            Log.d(Tag, "Threads=$threads → ${elapsed}ms (${MeasureRuns} runs, batch=$batchSize)")

            if (elapsed < bestTime) {
                bestTime = elapsed
                bestThreads = threads
            }
        }

        return bestThreads
    }

    private fun benchmarkWithThreads(
        env: OrtEnvironment,
        modelBytes: ByteArray,
        testInput: FloatArray,
        threads: Int,
        batchSize: Int
    ): Long {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = env.createSession(modelBytes, options)
        val inputName = session.inputNames.first()
        val shape = longArrayOf(batchSize.toLong(), 3, ImageEncoder.ImageSize.toLong(), ImageEncoder.ImageSize.toLong())

        try {
            // Warm up — let JIT and caches stabilize
            repeat(WarmUpRuns) {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(testInput), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { /* discard */ }
                }
            }

            // Measure
            val start = System.nanoTime()
            repeat(MeasureRuns) {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(testInput), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { /* discard */ }
                }
            }
            return (System.nanoTime() - start) / 1_000_000 // ms
        } finally {
            session.close()
        }
    }

    /**
     * Creates a synthetic [batchSize]x256x256 image tensor (all mid-gray).
     * We don't need real images — we're measuring compute throughput, not accuracy.
     */
    private fun createSyntheticInput(batchSize: Int): FloatArray {
        val size = ImageEncoder.ImageSize
        val floats = FloatArray(batchSize * 3 * size * size)
        // Fill with 0.5 (mid-gray, normalized)
        for (i in floats.indices) {
            floats[i] = 0.5f
        }
        return floats
    }
}
