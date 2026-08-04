package com.devomind.gallerysearch

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log

/**
 * Phase 3 memory budget enforcement.
 *
 * The spec asks that a WorkManager (background) run treat the RSS threshold more conservatively
 * than a foreground activity: the OS is more willing to kill a process with no visible UI under
 * memory pressure, and the CLIP+YuNet+MobileFaceNet pile is often the biggest memory contribution
 * on a mid-range device. We therefore unload the heavyweight MobileFaceNet session when available
 * memory dips below the threshold specific to the caller's context.
 *
 * Foreground policy: unload at FREE < 0.7× device's lowMemory threshold.
 * Background policy: unload at FREE < 1.3× device's lowMemory threshold (earlier, stricter).
 *
 * Both also unload on ComponentCallbacks2.TRIM_MEMORY_BACKGROUND / TRIM_MEMORY_COMPLETE.
 *
 * Once unloaded, OrtEnvironment persists but the OrtSession is closed (we keep the model bytes
 * short-lived via Application class). Next FaceEmbedder.use will rebuild the session lazily.
 */
class MemoryBudget(private val app: Application) {

    data class Report(
        val availableBytes: Long,
        val lowMemoryThresholdBytes: Long,
        val isLowMemory: Boolean,
        /** True when we're below the "unload now" threshold for the caller's current process role. */
        val shouldUnloadEmbedder: Boolean,
        /** Tuning knob: how far above the low-memory watermark the background threshold sits. */
        val backgroundMultiple: Float = BackgroundMultiple,
        /** Same knob for the foreground case. */
        val foregroundMultiple: Float = ForegroundMultiple
    )

    fun currentReport(isBackground: Boolean): Report {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val lowThreshold = info.threshold
        val multiple = if (isBackground) BackgroundMultiple else ForegroundMultiple
        val should = info.availMem < lowThreshold * multiple || info.lowMemory
        return Report(
            availableBytes = info.availMem,
            lowMemoryThresholdBytes = lowThreshold,
            isLowMemory = info.lowMemory,
            shouldUnloadEmbedder = should
        )
        // surface in logs so a BM tester sees the decision boundary
    }

    /** Call from an ongoing background run: if we should unload, close the shared embedder. */
    fun enforce(isBackground: Boolean) {
        val report = currentReport(isBackground)
        if (report.shouldUnloadEmbedder) {
            Log.w(Tag, "Memory pressure (${report.availableBytes}/${report.lowMemoryThresholdBytes}); unloading embedder.")
            (app as? GallerySearchApp)?.sharedEncoders?.closeFaceEmbedder()
        }
    }

    /** Register so trim callbacks propagate into the same enforcement. */
    fun attach() {
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

            override fun onLowMemory() {
                Log.w(Tag, "onLowMemory")
                (app as? GallerySearchApp)?.sharedEncoders?.closeFaceEmbedder()
            }

            override fun onTrimMemory(level: Int) {
                when (level) {
                    ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
                    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                        Log.w(Tag, "onTrimMemory level=$level, closing face embedder.")
                        (app as? GallerySearchApp)?.sharedEncoders?.closeFaceEmbedder()
                    }
                    else -> Unit
                }
            }
        })
    }

    companion object {
        private const val Tag = "MemoryBudget"

        /** Background is stricter — closer to the system low-memory watermark. */
        private const val BackgroundMultiple = 1.3f
        private const val ForegroundMultiple = 0.7f
    }
}
