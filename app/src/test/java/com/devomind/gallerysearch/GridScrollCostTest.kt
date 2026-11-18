package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the grid is allowed to do on the scroll path. Every one of these runs while a finger is
 * moving or a fling is in flight, so work that would be fine on a button press is a dropped frame
 * here: a whole-list diff when a page arrives mid-fling, a walk to the top of the list to find a
 * fast-scroll label nobody can see, a string built per row to name it.
 */
class GridScrollCostTest {

    private val adapter = source("ImageAdapter.kt")

    @Test
    fun aPageArrivingMidFlingIsARangeInsertNotAWholeListDiff() {
        assertTrue(
            "the adapter must offer an append that needs no diff",
            "fun appendCells(newCells: List<GalleryCell>)" in adapter
        )
        assertTrue(
            "an append is a range insert by construction",
            "notifyItemRangeInserted(start, newCells.size)" in adapter
        )
        val main = source("MainActivity.kt")
        // Both listings page: the browse timeline and search results.
        assertEquals(
            "every pagination path must append, not rebuild",
            2,
            Regex("""adapter\.appendCells\(""").findAll(main).count()
        )
        assertFalse(
            "pagination must never copy the list into a diff again",
            "adapter.updateCells(adapter.cells + " in main
        )
        // DiffUtil stays for the paths that genuinely reorder or remove: sorts, filters, deletions.
        assertTrue("structural changes still diff", "DiffUtil.calculateDiff" in adapter)
    }

    @Test
    fun aFastScrollLabelIsOnlySoughtWhileTheThumbIsHeld() {
        val scrolling = source("FastScrollIndicator.kt")
        assertTrue(
            "the header walk must not run on fling frames, where the bubble is not drawn",
            "if (isDragging) updateFromFraction(fraction)" in scrolling
        )
    }

    @Test
    fun aCellIsNamedWithoutBuildingItsName() {
        val stableIdFor = adapter
            .substringAfter("private fun stableIdFor(cell: GalleryCell): Long {")
            .substringBefore("\n    }")
        assertTrue("cells are named by folding their parts", "StableId.of(" in stableIdFor)
        assertFalse(
            "building the key as a string allocates one per comparison",
            "\${" in stableIdFor
        )
        // getItemId and DiffUtil both read this; they are on the scroll and notification paths.
        assertTrue("the id is still a stable, per-cell value", "getItemId(position: Int)" in adapter)
    }

    @Test
    fun foldingThePartsInPlaceNamesTheSameCellAsJoiningThem() {
        // The refactor above is only safe if it produces the old numbers: a changed id would make
        // RecyclerView treat every row as new and drop the scroll position on the next notify.
        val cases = listOf(
            listOf("photo:", "content://media/external/images/media/42"),
            listOf("header:", "March 2024", ":", "12 photos"),
            listOf("collage:", "content://a|content://b|content://c"),
            listOf("sort_row"),
            listOf("", "", "")
        )
        for (parts in cases) {
            assertEquals(
                "the id must not depend on how the key is assembled: $parts",
                legacyFold(parts.joinToString("")),
                StableId.of(*parts.toTypedArray())
            )
        }
    }

    private fun legacyFold(key: String): Long =
        key.fold(1125899906842597L) { hash, char -> 31L * hash + char.code.toLong() }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/ImageAdapter.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
