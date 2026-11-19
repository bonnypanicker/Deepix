package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The landing screen for a query is a stack of section cards, and each card already says how many
 * results it holds ("Smart · 25", "Albums · 12"). A result summary printed above that stack repeated
 * the same numbers a second time and cost a band of vertical space before the first card.
 *
 * These tests pin the split: the landing header stays empty (so `updateSearchHeaderVisibility`, which
 * collapses the row, removes the band), while a drilled-in section grid — which has no cards to carry
 * counts — keeps its label. The "no results" and filter summaries are unaffected.
 */
class SearchLandingHeaderTest {

    private val main = source("MainActivity.kt")

    @Test
    fun theLandingCardsCarryTheirCountsSoTheHeaderSaysNothing() {
        val landing = main
            .substringAfter("private fun showSearchLanding() {")
            .substringBefore("\n    }")
        assertTrue(
            "the landing must clear the summary so the row collapses above the cards",
            "setSearchResultSummary(\"\")" in landing
        )
        // None of the count formatting that used to live here may come back.
        for (retired in listOf("photos_count_summary", "result_count", "SearchSection.Albums.label")) {
            assertFalse(
                "the landing header must not print $retired: the cards already do",
                retired in landing
            )
        }
    }

    @Test
    fun aSectionGridStillNamesItselfBecauseItHasNoCards() {
        val counter = main
            .substringAfter("private fun updateSearchResultCount() {")
            .substringBefore("\n    }")
        assertTrue(
            "a drilled-in section grid labels the results it is showing",
            "section.section.label" in counter
        )
        assertTrue("its count is still formatted", "photos_count_summary" in counter)
        assertTrue("an empty grid still says so", "no_results" in counter)
    }

    @Test
    fun anEmptySummaryCollapsesTheRowAndThePanel() {
        val visibility = main
            .substringAfter("private fun updateSearchHeaderVisibility() {")
            .substringBefore("\n    }")
        assertTrue(
            "an empty summary must hide the header row, not leave a blank band",
            "hasSummary) View.VISIBLE else View.GONE" in visibility
        )
        assertTrue(
            "with no chips and no summary the whole panel goes away",
            "hasSummary || hasAlbumChips" in visibility
        )
        // The grid starts under the overlay, so a shorter overlay is a tighter grid with no
        // extra margin to maintain by hand.
        assertTrue(
            "the grid's top padding must follow the overlay's height",
            "top = binding.topOverlay.height" in main
        )
    }

    @Test
    fun lateRendersCannotPrintACountOverTheCards() {
        // Re-scoring arrives asynchronously and re-enters the landing; the only non-landing caller
        // that sets the summary must stay behind the landing guard.
        val guard = "currentMode == Mode.Search && !searchLandingVisible) updateSearchResultCount()"
        assertTrue("the section-count path stays behind the landing guard", guard in main)
        assertEquals(
            "the landing is rendered in exactly one place",
            1,
            Regex("""else \{\s*showSearchLanding\(\)""").findAll(main).count()
        )
    }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/MainActivity.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
