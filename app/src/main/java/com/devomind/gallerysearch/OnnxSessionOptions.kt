package com.devomind.gallerysearch

import android.util.Log
import ai.onnxruntime.OrtSession

object OnnxSessionOptions {

    private const val DefaultThreadCount = 4

    /**
     * Creates ORT session options with:
     * - Configurable thread count (from benchmark or default)
     * - NNAPI acceleration with FP16 flags when available
     * - Full graph optimization
     */
    fun create(tag: String, threadCount: Int = DefaultThreadCount): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            tryAddNnapiWithFlags(this, tag)
        }
    }

    /**
     * Tries to enable NNAPI in priority order:
     * 1. addNnapi(EnumSet<NNAPIFlags>) with USE_FP16 — best for FP16 models
     * 2. addNnapi() no-arg — basic NNAPI without special flags
     * 3. CPU fallback — if NNAPI is unavailable
     *
     * All via reflection for maximum compatibility across ORT versions.
     */
    private fun tryAddNnapiWithFlags(options: OrtSession.SessionOptions, tag: String) {
        // Attempt 1: Try the flags-based overload for FP16 acceleration
        try {
            // Look for the NNAPIFlags enum class
            val flagsClass = Class.forName("ai.onnxruntime.NNAPIFlags")
            if (flagsClass.isEnum) {
                @Suppress("UNCHECKED_CAST")
                val enumConstants = flagsClass.enumConstants as? Array<Enum<*>>
                val useFp16 = enumConstants?.firstOrNull { it.name == "USE_FP16" }

                if (useFp16 != null) {
                    // Build EnumSet with USE_FP16
                    @Suppress("UNCHECKED_CAST")
                    val enumSet = java.util.EnumSet.of(useFp16 as Nothing)

                    val method = options.javaClass.methods.firstOrNull {
                        it.name == "addNnapi" && it.parameterCount == 1 &&
                                it.parameterTypes[0] == java.util.EnumSet::class.java
                    }
                    if (method != null) {
                        method.invoke(options, enumSet)
                        Log.d(tag, "NNAPI enabled with USE_FP16 flag")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "NNAPI flags-based setup not available: ${e.message}")
        }

        // Attempt 2: No-arg addNnapi()
        try {
            val method = options.javaClass.methods.firstOrNull {
                it.name == "addNnapi" && it.parameterCount == 0
            } ?: return
            method.invoke(options)
            Log.d(tag, "NNAPI enabled (no flags)")
        } catch (e: Exception) {
            Log.w(tag, "NNAPI unavailable; using CPU.", e)
        }
    }
}
