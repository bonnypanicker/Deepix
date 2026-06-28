package com.devomind.gallerysearch

/**
 * Strong-reference hand-off of the candidate image list from MainActivity to
 * [SmartCleanupActivity]. Mirrors [ViewerItemsHolder] to avoid the Binder transaction size
 * limit when passing large lists between activities. Cleared by the cleanup screen once consumed.
 */
object CleanupHandoff {
    var items: List<GalleryRepository.MediaItem> = emptyList()
    var indexedCount: Int = 0

    fun release() {
        items = emptyList()
        indexedCount = 0
    }
}
