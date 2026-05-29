package com.devomind.gallerysearch

import android.net.Uri

class SearchResultManager(private val allResults: List<Uri>) {

    private var currentPage = 0
    val isLastPage get() = (currentPage + 1) * SearchTuning.PageSize >= allResults.size
    val totalCount get() = allResults.size

    fun firstPage(): List<Uri> {
        currentPage = 0
        return getPage(0)
    }

    fun nextPage(): List<Uri> {
        if (isLastPage) return emptyList()
        currentPage++
        return getPage(currentPage)
    }

    private fun getPage(page: Int): List<Uri> {
        val from = page * SearchTuning.PageSize
        val to   = minOf(from + SearchTuning.PageSize, allResults.size)
        return allResults.subList(from, to)
    }
}
