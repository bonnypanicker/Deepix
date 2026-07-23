package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compatibility shim: runtime benchmarking is disabled; we always use 6 threads. */
object ThreadBenchmark {

    private const val Tag = "ThreadBenchmark"
    const val FixedThreadCount = 6

    suspend fun getOrBenchmark(context: Context): Int {
        val cached = IndexPreferences.getOptimalThreadCount(context)
        if (cached > 0) {
            Log.d(Tag, "Using cached optimal thread count: $cached")
            return cached
        }

        return withContext(Dispatchers.IO) {
            IndexPreferences.saveOptimalThreadCount(context, FixedThreadCount)
            Log.d(Tag, "Runtime benchmark disabled; using fixed thread count: $FixedThreadCount")
            FixedThreadCount
        }
    }
}
