package com.devomind.gallerysearch

import android.net.Uri

data class FolderNode(
    val path: String,
    val name: String,
    val depth: Int,
    val coverUri: Uri?,
    val itemCount: Int,
    val directItems: List<GalleryRepository.MediaItem> = emptyList(),
    val expanded: Boolean = true,
    val children: List<FolderNode> = emptyList()
) {
    val isLeaf: Boolean
        get() = children.isEmpty()
}
