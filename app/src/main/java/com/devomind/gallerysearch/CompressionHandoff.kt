package com.devomind.gallerysearch

import android.net.Uri

/**
 * Strong-reference hand-off from SmartCleanupActivity to CompressionActivity: the full photo list
 * (already loaded for cleanup) plus the URIs the user selected for compression. Avoids re-querying
 * MediaStore and the Binder transaction limit of Intent extras.
 */
object CompressionHandoff {
    var items: List<GalleryRepository.MediaItem> = emptyList()
    var selectedUris: List<Uri> = emptyList()

    fun release() {
        items = emptyList()
        selectedUris = emptyList()
    }
}
