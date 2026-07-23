package com.devomind.gallerysearch

import android.util.Log
import ai.onnxruntime.OrtSession

object OnnxSessionOptions {

    const val DefaultThreadCount = 6

    /**
     * Creates ORT session options with:
     * - Configurable thread count
     * - Full graph optimization
     *
     * NNAPI is intentionally disabled because software-reference fallback
     * increases warm-up time and memory pressure on some devices.
     */
    fun create(tag: String, threadCount: Int = DefaultThreadCount): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            Log.d(tag, "Using ORT CPU/XNNPACK path (intra-op threads=$threadCount)")
        }
    }
}
