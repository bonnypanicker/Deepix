package com.devomind.gallerysearch

internal data class InitResult(
    val imageEncoder: ImageEncoder,
    val textEncoder: TextEncoder,
    val repository: GalleryRepository,
    val snapshot: LibrarySnapshot
)

internal data class LibrarySnapshot(
    val albums: List<GalleryRepository.Album>,
    val imageItems: List<GalleryRepository.MediaItem>,
    val collectionItems: List<GalleryRepository.MediaItem>,
    val videoItems: List<GalleryRepository.MediaItem>
)

internal enum class Mode { Browse, Search, AlbumDetail, FolderDetail, SmartAlbumDetail }

internal enum class Section { Collection, Videos, Albums, Favorites, Folders }
