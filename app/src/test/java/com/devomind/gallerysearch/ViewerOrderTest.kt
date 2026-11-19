package com.devomind.gallerysearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tapping a photo and swiping must walk the photos in the order the grid shows them.
 *
 * The viewer used to be handed the raw section snapshots — always newest-first — while the grid
 * sorted its own copy. Under any other order the next photo in the viewer was the newest one rather
 * than the one beside the tapped tile, so plain swiping felt like the wrong pictures were opening.
 *
 * Paged listings (sections, album detail, folder detail, collection albums) sort once inside
 * [MainActivity.renderPagedTimeline] and build the cells and `pagedItems` from that one list; search
 * sorts into `fullSearchResults` and builds its cells from it. These tests pin those two as the
 * viewer's sources, and pin the invariants that make them trustworthy.
 */
class ViewerOrderTest {

    private val main = source("MainActivity.kt")

    private val viewerItems = main
        .substringAfter("private fun currentViewerItems()")
        .substringBefore("\n    }")

    @Test
    fun theViewerWalksTheListingTheGridSorted() {
        assertTrue("browse pages page the list the timeline sorted", "pagedItems" in viewerItems)
        assertTrue("search pages its filtered, sorted results", "fullSearchResults" in viewerItems)
        // The snapshots are the unsorted input to the sort. Reading them here is the bug: they are in
        // newest-first order no matter which order the grid is showing.
        for (raw in listOf(
            "collectionItems",
            "videoItems",
            "favoriteItems",
            "albumDetailItems",
            "folderDetailItems()"
        )) {
            assertFalse("$raw is newest-first, not the order on screen", raw in viewerItems)
        }
        // When no listing owns the grid, the rendered cells are the only honest order left.
        assertTrue("the fallback is what is actually rendered", "adapter.cells.asSequence()" in viewerItems)
    }

    @Test
    fun searchIsDecidedBeforeTheBrowseListingThatOutlivesItsPage() {
        // pagedItems is not cleared when search opens; a search that read it after opening a book
        // would page through that book's photos while the grid shows query hits.
        val searchAt = viewerItems.indexOf("currentMode == Mode.Search")
        val pagedAt = viewerItems.indexOf("pagedItems")
        assertTrue("search must be checked first", searchAt >= 0 && searchAt < pagedAt)
    }

    @Test
    fun thePagedListIsTheSameSortedListTheCellsWereBuiltFrom() {
        // Order lives in exactly one place: renderPagedTimeline sorts, cuts the first page and
        // builds the cells from that list, then keeps it as pagedItems. If these drift apart the
        // viewer pages a different sequence than the one on screen.
        assertTrue(
            "the page is cut from the sorted list",
            "pageEndWithin(sorted, 0, sortOption.dateOrdered)" in main
        )
        assertTrue(
            "the cells come from that same list",
            "FirstPage(sorted, cells, lastDay, to)" in main
        )
        assertTrue(
            "and it is what pagedItems holds",
            "pagedItems = page.items" in main
        )
    }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()
            .replace("\r\n", "\n")

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/MainActivity.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
