package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Typing must not make the cards disappear and come back.
 *
 * Every keystroke used to replace the grid with a single "Searching your library" cell, and the
 * debounced search then replaced that with the result again — one flash per key, and two full
 * rebinds per query because `submitSearch` cleared the presentation a second time before starting.
 *
 * The grid now keeps what it is showing and lets the render replace it in place. The searching row
 * survives only where there is nothing to keep: the pre-query hints on the first query, the empty
 * state's absence, an older loading row. This test pins that split, so a future "clear everything
 * before searching" cannot quietly reintroduce the flash.
 */
class SearchKeystrokeStabilityTest {

    private val main = source("MainActivity.kt")
    private val adapter = source("ImageAdapter.kt")

    @Test
    fun aChangedQueryDetachesItsDataWithoutWipingTheResultOnScreen() {
        val reset = main
            .substringAfter("private fun clearSearchPresentationForPendingInput() {")
            .substringBefore("\n    }")
        assertFalse(
            "typing must not replace the rendered cells",
            "adapter.replaceCells" in reset
        )
        assertTrue(
            "the searching row is reached through the one helper that decides",
            "showSearchPending()" in reset
        )
        // The data owners still have to go: a paged-in photo or an opened card from the previous
        // query would otherwise outlive the text that produced it.
        for (owner in listOf("searchResultsMaster", "fullSearchResults", "currentDisplayedSearchResultCount")) {
            assertTrue("$owner must still be detached from the changed query", owner in reset)
        }
        assertTrue("the old sections must still be dropped", "clearSearchSections()" in reset)
    }

    @Test
    fun theSearchingRowOnlyReplacesContentWithNoResultInIt() {
        val pending = main
            .substringAfter("private fun showSearchPending() {")
            .substringBefore("\n    }")
        assertTrue(
            "a result on screen must never be wiped for the pending row",
            "if (adapter.isShowingResults()) return" in pending
        )
        assertTrue(
            "the first query of a session still gets its searching feedback",
            "GalleryCell.Loading(getString(R.string.search_loading))" in pending
        )
    }

    @Test
    fun noOtherPathBlanksTheGridWhileAQueryIsPending() {
        // One construction site, so there is one place to change the rule.
        assertEquals(
            "the searching row must be built in exactly one place",
            1,
            Regex("""GalleryCell\.Loading\(getString\(R\.string\.search_loading\)\)""").findAll(main).count()
        )
        // Both the debounced search's interim stage and the image-similarity pass go through it.
        assertEquals(
            "every pending path must ask the helper",
            3,
            Regex("""^\s+showSearchPending\(\)\s*$""", RegexOption.MULTILINE).findAll(main).count()
        )
    }

    @Test
    fun theAdapterKnowsWhatCountsAsAResult() {
        val predicate = adapter
            .substringAfter("fun isShowingResults(): Boolean")
            .substringBefore("\n\n")
        for (cell in listOf("GalleryCell.Photo", "GalleryCell.Collage", "GalleryCell.AlbumCell",
            "GalleryCell.FolderCell", "GalleryCell.SearchSection")) {
            assertTrue("$cell is a result the user is reading", cell in predicate)
        }
        // Hints and the pending row are not results, so they may still be replaced.
        for (notAResult in listOf("SuggestionChips", "TimeFilters", "Loading", "Empty")) {
            assertFalse("$notAResult must not be treated as a result", notAResult in predicate)
        }
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
