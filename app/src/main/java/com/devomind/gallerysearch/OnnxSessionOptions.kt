package com.devomind.gallerysearch

import android.util.Log
import ai.onnxruntime.OrtSession

object OnnxSessionOptions {

    private const val DefaultThreadCount = 4

    /**
     * Creates ORT session options with:
     * - Configurable thread count (from benchmark or default)
     * - Graph optimization and caching
     * - Specific EP routing
     */
    fun create(
        tag: String,
        threadCount: Int = DefaultThreadCount,
        ep: ExecutionProviderSelector.Ep? = null,
        cacheDir: String? = null
    ): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threadCount)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setMemoryPatternOptimization(true)
            
            if (cacheDir != null) {
                val optimizedModelPath = "$cacheDir/optimized_$tag.onnx"
                setOptimizedModelFilePath(optimizedModelPath)
            }

            when (ep) {
                ExecutionProviderSelector.Ep.QNN_HTP -> tryAddQnnHtp(this, tag)
                ExecutionProviderSelector.Ep.NNAPI -> tryAddNnapiWithFlags(this, tag)
                ExecutionProviderSelector.Ep.XNNPACK -> tryAddXnnpack(this, tag, threadCount)
                ExecutionProviderSelector.Ep.CPU -> Log.d(tag, "Using CPU EP")
                null -> tryAddNnapiWithFlags(this, tag)
            }
        }
    }

    fun recommendedBatchSize(ep: ExecutionProviderSelector.Ep?): Int {
        return when (ep) {
            ExecutionProviderSelector.Ep.QNN_HTP,
            ExecutionProviderSelector.Ep.NNAPI -> 1 // NPUs often prefer bs=1
            ExecutionProviderSelector.Ep.XNNPACK,
            ExecutionProviderSelector.Ep.CPU,
            null -> 4
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
            Log.w(tag, "NNAPI unavailable.", e)
        }
    }

    private fun tryAddXnnpack(options: OrtSession.SessionOptions, tag: String, threadCount: Int) {
        try {
            val method = options.javaClass.methods.firstOrNull {
                it.name == "addXnnpack" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Map::class.java
            }
            if (method != null) {
                val config = mapOf("intra_op_num_threads" to threadCount.toString())
                method.invoke(options, config)
                Log.d(tag, "XNNPACK enabled")
            }
        } catch (e: Exception) {
            Log.d(tag, "XNNPACK unavailable: ${e.message}")
        }
    }

    private fun tryAddQnnHtp(options: OrtSession.SessionOptions, tag: String) {
        try {
            val method = options.javaClass.methods.firstOrNull {
                it.name == "addQnn" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Map::class.java
            }
            if (method != null) {
                val config = mapOf(
                    "backend_path" to "libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "htp_graph_finalization_optimization_mode" to "3"
                )
                method.invoke(options, config)
                Log.d(tag, "QNN HTP enabled")
            }
        } catch (e: Exception) {
            Log.d(tag, "QNN HTP unavailable: ${e.message}")
        }
    }
}
