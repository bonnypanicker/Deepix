package com.devomind.gallerysearch

import android.app.Application
import kotlin.concurrent.thread

class GallerySearchApp : Application() {
    val sharedEncoders: SharedEncoders by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedEncoders(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // Enforce the Recycle Bin's 30-day retention off the main thread on each cold start.
        thread(isDaemon = true) { runCatching { BinManager.purgeExpired(applicationContext) } }
    }
}

class SharedEncoders(private val context: android.content.Context) {
    @Volatile private var imageEncoder: ImageEncoder? = null
    @Volatile private var textEncoder: TextEncoder? = null

    fun getImageEncoder(): ImageEncoder {
        return imageEncoder ?: synchronized(this) {
            imageEncoder ?: ImageEncoder(context).also { imageEncoder = it }
        }
    }

    fun getTextEncoder(): TextEncoder {
        return textEncoder ?: synchronized(this) {
            textEncoder ?: TextEncoder(context).also { textEncoder = it }
        }
    }
}
