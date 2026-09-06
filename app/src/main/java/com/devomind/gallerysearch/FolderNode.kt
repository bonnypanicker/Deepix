package com.devomind.gallerysearch

import android.net.Uri

data class FolderNode(
    val path: String,
    val name: String,
    val depth: Int,
    val coverUri: Uri?,
    val itemCount: Int,
    val imageCount: Int,
    val videoCount: Int,
    val folderCount: Int,
    val sizeBytes: Long,
    val latestDateMillis: Long,
    val directItems: List<GalleryRepository.MediaItem> = emptyList(),
    val expanded: Boolean = true,
    val children: List<FolderNode> = emptyList()
) {
    val isLeaf: Boolean
        get() = children.isEmpty()

    /**
     * Returns a copy with [uris] filtered out of every level's [directItems], rolling the
     * removed items out of the aggregate counts. Folder nodes snapshot their own item lists, so
     * without this an optimistic delete would reappear on the next folder re-render. A cover
     * pointing at a removed photo falls back to the first remaining item.
     */
    fun withoutUris(uris: Set<Uri>): FolderNode {
        if (uris.isEmpty()) return this
        val keptDirect = directItems.filterNot { it.uri in uris }
        val keptChildren = children.map { it.withoutUris(uris) }
        val removedDirectImages = directItems.count {
            it.uri in uris && it.mediaType != GalleryRepository.MediaType.Video
        }
        val removedDirectVideos = directItems.count {
            it.uri in uris && it.mediaType == GalleryRepository.MediaType.Video
        }
        val removedImages = removedDirectImages +
            (children.sumOf { it.imageCount } - keptChildren.sumOf { it.imageCount })
        val removedVideos = removedDirectVideos +
            (children.sumOf { it.videoCount } - keptChildren.sumOf { it.videoCount })
        return copy(
            itemCount = (itemCount - removedImages - removedVideos).coerceAtLeast(0),
            imageCount = (imageCount - removedImages).coerceAtLeast(0),
            videoCount = (videoCount - removedVideos).coerceAtLeast(0),
            coverUri = if (coverUri !in uris) coverUri
                else keptDirect.firstOrNull()?.uri
                    ?: keptChildren.firstNotNullOfOrNull { it.coverUri },
            directItems = keptDirect,
            children = keptChildren
        )
    }
}
