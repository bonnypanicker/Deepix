package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

object ExecutionProviderSelector {
    private const val Tag = "EPSelector"

    enum class Ep { QNN_HTP, NNAPI, XNNPACK, CPU }

    /** Returns the cached or freshly-probed best EP for this device. */
    fun getOrSelect(context: Context): Ep {
        IndexPreferences.getChosenEp(context)?.let { return it }
        val chosen = probe(context)
        IndexPreferences.saveChosenEp(context, chosen)
        return chosen
    }

    private fun probe(context: Context): Ep {
        val env = OrtEnvironment.getEnvironment()
        val modelBytes = AssetUtils.readAssetBytes(context, "vision_model_fp16.onnx")
        val inputSize = ImageEncoder.ImageSize * ImageEncoder.ImageSize * 3
        val input = FloatArray(inputSize) { 0.5f }

        // Reference output on plain CPU for correctness comparison.
        val reference = runOnce(env, modelBytes, input, Ep.CPU) ?: return Ep.CPU

        var best = Ep.CPU
        var bestMs = Long.MAX_VALUE
        for (ep in listOf(Ep.QNN_HTP, Ep.NNAPI, Ep.XNNPACK)) {
            val (ms, out) = timeAndRun(env, modelBytes, input, ep) ?: continue
            val cos = out?.let { cosine(reference, it) } ?: 0f
            Log.d(Tag, "$ep -> ${ms}ms, cosine=$cos")
            if (cos >= 0.99f && ms < bestMs) { 
                bestMs = ms
                best = ep 
            }
        }
        Log.d(Tag, "Selected EP: $best")
        return best
    }

    private fun runOnce(env: OrtEnvironment, modelBytes: ByteArray, input: FloatArray, ep: Ep): FloatArray? {
        return timeAndRun(env, modelBytes, input, ep)?.second
    }

    private fun timeAndRun(env: OrtEnvironment, modelBytes: ByteArray, input: FloatArray, ep: Ep): Pair<Long, FloatArray?>? {
        return runCatching {
            val options = OnnxSessionOptions.create(Tag, 4, ep, null)
            env.createSession(modelBytes, options).use { session ->
                val inputName = session.inputNames.first()
                val shape = longArrayOf(1, 3, ImageEncoder.ImageSize.toLong(), ImageEncoder.ImageSize.toLong())
                
                // Warmup
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { }
                }

                val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                val out = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val value = result.get(session.outputNames.first()).get().value
                        OnnxOutput.flattenFloatArray(value)
                    }
                }
                val ms = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
                Pair(ms, out)
            }
        }.onFailure { Log.d(Tag, "EP $ep failed during probe: ${it.message}") }.getOrNull()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / (Math.sqrt((na*nb).toDouble())).toFloat()
    }
}
