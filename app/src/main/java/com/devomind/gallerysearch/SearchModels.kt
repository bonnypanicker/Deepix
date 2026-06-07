package com.devomind.gallerysearch

import android.net.Uri

enum class SearchMatchSource {
    Ai,
    Metadata
}

data class SearchMatch(
    val uri: Uri,
    val aiScore: Float? = null,
    val metadataScore: Int = 0,
    val sources: Set<SearchMatchSource>
) {
    val hasAi: Boolean get() = SearchMatchSource.Ai in sources
    val hasMetadata: Boolean get() = SearchMatchSource.Metadata in sources
}
