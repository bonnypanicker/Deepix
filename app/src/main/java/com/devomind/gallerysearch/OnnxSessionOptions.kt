package com.devomind.gallerysearch

import android.util.Log
import ai.onnxruntime.OrtSession

object OnnxSessionOptions {
    fun create(tag: String): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            tryAddNnapi(this, tag)
        }
    }

    private fun tryAddNnapi(options: OrtSession.SessionOptions, tag: String) {
        runCatching {
            val method = options.javaClass.methods.firstOrNull {
                it.name == "addNnapi" && it.parameterCount == 0
            } ?: return
            method.invoke(options)
        }.onFailure { error ->
            Log.w(tag, "NNAPI unavailable; using CPU.", error)
        }
    }
}
