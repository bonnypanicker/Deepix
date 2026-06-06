package com.devomind.gallerysearch

import java.lang.ref.WeakReference

object ViewerItemsHolder {
    private var ref: WeakReference<List<GalleryRepository.MediaItem>>? = null

    fun store(items: List<GalleryRepository.MediaItem>) {
        ref = WeakReference(items)
    }

    fun retrieve(stringUri: String): List<GalleryRepository.MediaItem>? {
        val items = ref?.get()
        if (items != null && items.any { it.uri.toString() == stringUri }) {
            return items
        }
        return null
    }

    fun release() {
        ref = null
    }
}
