package com.devomind.gallerysearch

import android.app.Application

class GallerySearchApp : Application() {
    val sharedEncoders: SharedEncoders by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SharedEncoders(applicationContext)
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
