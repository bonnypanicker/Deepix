package com.devomind.gallerysearch

import android.util.Log
import ai.onnxruntime.OrtSession

object OnnxSessionOptions {

    const val DefaultThreadCount = 6

    enum class ModelFormat { ONNX, ORT }

    /**
     * Creates ORT session options with:
     * - Configurable thread count
     * - Full graph optimization
     *
     * NNAPI is intentionally disabled because software-reference fallback
     * increases warm-up time and memory pressure on some devices.
     */
    fun create(
        tag: String,
        threadCount: Int = DefaultThreadCount,
        modelFormat: ModelFormat = ModelFormat.ONNX
    ): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (modelFormat == ModelFormat.ORT) {
                // The byte-array API can detect ORT models from their header, but declaring the
                // format makes the CLIP model contract explicit and prevents an accidental ONNX
                // asset fallback from silently changing the production load path.
                addConfigEntry("session.load_model_format", "ORT")
            }
            Log.d(tag, "Using ORT CPU/XNNPACK path (intra-op threads=$threadCount, model=$modelFormat)")
        }
    }
}
