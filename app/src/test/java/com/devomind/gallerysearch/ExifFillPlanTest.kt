package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * The "Locations" section used to re-read EXIF for the same 150 already-stored photos on every
 * search: `fillMissingExif` put the existing rows *after* the unread ones and `.take(150)` always
 * started again from the top of the list. That is a permanent cost — 150 MediaProvider streams per
 * keystroke — and it also re-read rows with `setRequireOriginal` after the location permission had
 * been revoked, upserting an all-null EXIF row over good GPS.
 *
 * These tests pin the replacement: unread photos first, then a one-way cursor over the stored rows,
 * so a library whose rows already exist costs nothing after a single sweep.
 */
class ExifFillPlanTest {

    private val limit = 150

    /** Zero-padded so the planner's sorted sweep order reads the same as the numeric order. */
    private fun uri(n: Int) = "content://media/photo/" + n.toString().padStart(3, '0')

    @Test
    fun aColdLibraryFillsTheNewestPhotosUpToTheLimit() {
        val plan = planExifFill((1..300).map { uri(it) }, emptySet(), 0, limit, true)

        assertEquals((1..150).map { uri(it) }, plan.fresh)
        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(0, plan.nextCursor)
    }

    @Test
    fun onceEveryRowExistsTheSearchRevisitsRowsItHasNotRevisitedYet() {
        val lib = (1..300).map { uri(it) }

        val plan = planExifFill(lib, lib.toSet(), 0, limit, true)

        assertEquals(emptyList<String>(), plan.fresh)
        assertEquals((1..150).map { uri(it) }, plan.revisits)
        assertEquals(150, plan.nextCursor)
    }

    @Test
    fun theNextSearchContinuesFromTheCursorInsteadOfTheFirstRow() {
        val lib = (1..300).map { uri(it) }

        val plan = planExifFill(lib, lib.toSet(), 150, limit, true)

        assertEquals((151..300).map { uri(it) }, plan.revisits)
        assertEquals(300, plan.nextCursor)
    }

    @Test
    fun aFinishedSweepStopsRevisitingAndLeavesTheCursorAlone() {
        val lib = (1..300).map { uri(it) }

        val plan = planExifFill(lib, lib.toSet(), 300, limit, true)

        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(emptyList<String>(), plan.uris)
        assertEquals(300, plan.nextCursor)
    }

    @Test
    fun withoutLocationPermissionNoRowIsRevisited() {
        val lib = (1..300).map { uri(it) }

        val plan = planExifFill(lib, lib.toSet(), 40, limit, revisitsAllowed = false)

        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(40, plan.nextCursor)
    }

    @Test
    fun freshRowsAreReadBeforeRevisits() {
        val lib = (1..300).map { uri(it) }
        val existing = (101..300).map { uri(it) }.toSet()

        val plan = planExifFill(lib, existing, 0, limit, true)

        assertEquals((1..100).map { uri(it) }, plan.fresh)
        assertEquals((101..150).map { uri(it) }, plan.revisits)
        assertEquals(50, plan.nextCursor)
    }

    @Test
    fun thePlanIsOneListInFreshThenRevisitOrder() {
        val lib = (1..300).map { uri(it) }
        val existing = (101..300).map { uri(it) }.toSet()

        val plan = planExifFill(lib, existing, 0, limit, true)

        assertEquals((1..150).map { uri(it) }, plan.uris)
    }

    @Test
    fun revisitsDoNotDependOnTheLibraryOrder() {
        val lib = (1..300).map { uri(it) }
        val shuffled = lib.shuffled(Random(7))

        val ordered = planExifFill(lib, lib.toSet(), 150, limit, true).revisits
        val scrambled = planExifFill(shuffled, lib.toSet(), 150, limit, true).revisits

        // The pool order changes between searches (filters, album scope); the sweep must not skip or
        // repeat rows just because the library list is a different order this time.
        assertEquals(ordered, scrambled)
    }

    @Test
    fun anEmptyLibraryPlansNothing() {
        val plan = planExifFill(emptyList(), emptySet(), 0, limit, true)

        assertEquals(emptyList<String>(), plan.uris)
        assertEquals(0, plan.nextCursor)
    }

    @Test
    fun aCursorPastTheEndIsClamped() {
        val lib = (1..10).map { uri(it) }

        val plan = planExifFill(lib, lib.toSet(), 99, limit, true)

        assertEquals(emptyList<String>(), plan.revisits)
        assertEquals(10, plan.nextCursor)
    }

    @Test
    fun aZeroLimitPlansNothing() {
        val plan = planExifFill((1..10).map { uri(it) }, emptySet(), 3, 0, true)

        assertEquals(emptyList<String>(), plan.uris)
        assertEquals(3, plan.nextCursor)
    }
}
