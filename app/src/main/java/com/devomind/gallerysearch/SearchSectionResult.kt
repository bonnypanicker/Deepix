package com.devomind.gallerysearch

enum class SearchSection(val label: String) {
    Smart("Smart"),
    Metadata("Metadata"),
    Albums("Albums"),
    Tags("Tags"),
    People("People"),
    Locations("Locations")
}

data class SearchSectionResult(
    val section: SearchSection,
    val count: Int,
    val results: List<PhotoSearchResult>
)
