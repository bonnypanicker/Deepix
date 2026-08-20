package com.devomind.gallerysearch

import android.net.Uri

/**
 * Models backing the search page's pre-query empty state (banner, suggestion chips, people row,
 * quick time filters, content shortcuts, recent searches). Rendered as full-span GalleryCells.
 */

/** Visual state of the indexing banner. */
enum class IndexBannerStatus {
    /** CLIP pass running with a known total — determinate "X / Y" bar. */
    Running,

    /** Work started but hasn't reported a total yet. */
    Starting,

    /** Enqueued/blocked on constraints (e.g. waiting to charge). */
    Queued,

    /** User-paused with partial progress. */
    Paused
}

/** One face-cluster entry in the "Search by person" row. */
data class SearchPersonPreview(
    val personId: Long,
    val displayName: String,
    val photoUri: Uri?,
    val bboxJson: String?,
    val detectionWidth: Int,
    val detectionHeight: Int
)

/** A quick date filter chip; a blank [query] means "open the year picker" instead of searching. */
data class SearchTimeFilter(
    val label: String,
    val query: String
)

/** Content-type shortcuts shown on the empty search screen. */
enum class SearchShortcut {
    Videos,
    Screenshots,
    Favorites,
    Selfies,
    RecentlyDeleted
}

data class SearchShortcutItem(
    val shortcut: SearchShortcut,
    val label: String,
    val iconRes: Int
)
