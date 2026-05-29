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
        val modelBytes = AssetUtils.readAssetBytes(context, "vision_model_fp16.onnx")
        val testTensor = createSyntheticInput(env)

        var bestThreads = 4 // safe default
        var bestTime = Long.MAX_VALUE

        for (threads in ThreadCandidates) {
            val elapsed = try {
                benchmarkWithThreads(env, modelBytes, testTensor, threads)
            } catch (e: Exception) {
                Log.w(Tag, "Benchmark failed for threads=$threads", e)
                Long.MAX_VALUE
            }

            Log.d(Tag, "Threads=$threads → ${elapsed}ms (${MeasureRuns} runs)")

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
        threads: Int
    ): Long {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val session = env.createSession(modelBytes, options)
        val inputName = session.inputNames.first()

        try {
            // Warm up — let JIT and caches stabilize
            repeat(WarmUpRuns) {
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(testInput),
                    longArrayOf(1, 3, ImageEncoder.ImageSize.toLong(), ImageEncoder.ImageSize.toLong())
                ).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { /* discard */ }
                }
            }

            // Measure
            val start = System.nanoTime()
            repeat(MeasureRuns) {
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(testInput),
                    longArrayOf(1, 3, ImageEncoder.ImageSize.toLong(), ImageEncoder.ImageSize.toLong())
                ).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { /* discard */ }
                }
            }
            return (System.nanoTime() - start) / 1_000_000 // ms
        } finally {
            session.close()
        }
    }

    /**
     * Creates a synthetic 256×256 image tensor (all mid-gray).
     * We don't need a real image — we're measuring compute throughput, not accuracy.
     */
    private fun createSyntheticInput(env: OrtEnvironment): FloatArray {
        val size = ImageEncoder.ImageSize
        val planeSize = size * size
        val floats = FloatArray(3 * planeSize)
        // Fill with 0.5 (mid-gray, normalized)
        for (i in floats.indices) {
            floats[i] = 0.5f
        }
        return floats
    }
}
