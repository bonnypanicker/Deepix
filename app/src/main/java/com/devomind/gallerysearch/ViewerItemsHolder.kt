package com.devomind.gallerysearch

/**
 * Hands the (potentially large) media list to [ViewerActivity] without putting it through an
 * Intent's Binder transaction. A strong reference is held intentionally — a [java.lang.ref.WeakReference]
 * could be collected under memory pressure during the shared-element transition, leaving the viewer
 * empty. [ViewerActivity] clears it via [release] as soon as it has copied the list.
 */
object ViewerItemsHolder {
    private var items: List<GalleryRepository.MediaItem>? = null

    fun store(items: List<GalleryRepository.MediaItem>) {
        this.items = items
    }

    fun retrieve(stringUri: String): List<GalleryRepository.MediaItem>? {
        val current = items ?: return null
        return if (current.any { it.uri.toString() == stringUri }) current else null
    }

    fun release() {
        items = null
    }
}
