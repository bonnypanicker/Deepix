package com.devomind.gallerysearch

import android.app.Application
import android.os.StrictMode
import kotlin.concurrent.thread

class GallerySearchApp : Application() {
    val sharedEncoders: SharedEncoders by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedEncoders(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Surface main-thread disk/network and leaked closables during development only.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
        // Enforce the Recycle Bin's 30-day retention off the main thread on each cold start.
        thread(isDaemon = true) { runCatching { BinManager.purgeExpired(applicationContext) } }
    }
}

class SharedEncoders(private val context: android.content.Context) {
    @Volatile private var imageEncoder: ImageEncoder? = null
    @Volatile private var textEncoder: TextEncoder? = null

    /** Thread count tuned by [ThreadBenchmark]; falls back to the default until it has run. */
    private fun optimalThreadCount(): Int {
        return IndexPreferences.getOptimalThreadCount(context).takeIf { it > 0 }
            ?: OnnxSessionOptions.DefaultThreadCount
    }

    fun getImageEncoder(): ImageEncoder {
        return imageEncoder ?: synchronized(this) {
            imageEncoder ?: ImageEncoder(context, optimalThreadCount()).also { imageEncoder = it }
        }
    }

    fun getTextEncoder(): TextEncoder {
        return textEncoder ?: synchronized(this) {
            textEncoder ?: TextEncoder(context, optimalThreadCount()).also { textEncoder = it }
        }
    }
}
