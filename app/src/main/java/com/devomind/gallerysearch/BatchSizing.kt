package com.devomind.gallerysearch

import android.app.ActivityManager
import android.content.Context

/**
 * Single source of truth for the device-scaled index batch size, shared by [GalleryRepository]
 * (actual indexing) and [ThreadBenchmark] (must benchmark the same workload shape it will apply).
 */
object BatchSizing {
    fun computeBatchSize(context: Context): Int {
        IndexPreferences.getIndexBatchSizeOverride(context).takeIf { it > 0 }?.let { return it }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return when {
            activityManager.isLowRamDevice -> 2
            activityManager.memoryClass >= 192 -> 6
            else -> 4
        }
    }
}
