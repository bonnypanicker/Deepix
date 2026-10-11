package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free tier's edges, as numbers. These are the lines a paying user is promised and a free user is
 * held to, so every boundary is approached from both sides — including the one that matters most after a
 * purchase: that `unlocked = true` unlocks anything at all, which is the bug a limit turns into when the
 * flag gets dropped at one call site.
 */
class FreeTierTest {

    @Test
    fun theFirstThreeAccentsAreFreeAndEverythingAfterIsNot() {
        val free = AccentPalette.choices.take(FreeTier.FreeAccents)
        val paid = AccentPalette.choices.drop(FreeTier.FreeAccents)
        assertTrue(free.isNotEmpty() && paid.isNotEmpty())
        free.forEach { assertFalse("$it belongs to the free tier", FreeTier.accentLocked(it, false)) }
        paid.forEach { assertTrue("$it is paid", FreeTier.accentLocked(it, false)) }
        // The system accent is paid like any other, and it sits past the free cut by declaration order.
        assertTrue(FreeTier.accentLocked(AccentPalette.Choice.SYSTEM, false))
        assertEquals(AccentPalette.choices.last(), AccentPalette.Choice.SYSTEM)
    }

    @Test
    fun owningTheUnlockFreesEveryAccent() {
        AccentPalette.choices.forEach { assertFalse(FreeTier.accentLocked(it, true)) }
    }

    @Test
    fun theSafeCountsWhatIsAlreadyInIt() {
        assertEquals(FreeTier.FreeSafePhotos, FreeTier.safeRoom(0, false))
        assertEquals(1, FreeTier.safeRoom(FreeTier.FreeSafePhotos - 1, false))
        assertEquals(0, FreeTier.safeRoom(FreeTier.FreeSafePhotos, false))
        // A vault that arrived past the cap (Auto Backup, a device transferred from a paid user) must not
        // produce a negative room: List.take() throws on one, and this runs on the import path.
        assertEquals(0, FreeTier.safeRoom(FreeTier.FreeSafePhotos + 25, false))
    }

    @Test
    fun anUnlockedSafeTruncatesNothing() {
        assertEquals(Int.MAX_VALUE, FreeTier.safeRoom(FreeTier.FreeSafePhotos + 500, true))
        assertEquals(Int.MAX_VALUE, FreeTier.safeRoom(0, true))
    }

    @Test
    fun theAlbumCapBlocksOnReachingItAndNotBefore() {
        assertFalse(FreeTier.smartAlbumsBlocked(FreeTier.FreeSmartAlbums - 1, false))
        assertTrue(FreeTier.smartAlbumsBlocked(FreeTier.FreeSmartAlbums, false))
        // Past the cap the free user stays blocked — a device restored with eight albums cannot create a
        // ninth by deleting one and re-adding, because the rule counts what exists, not what was made here.
        assertTrue(FreeTier.smartAlbumsBlocked(FreeTier.FreeSmartAlbums + 4, false))
        assertFalse(FreeTier.smartAlbumsBlocked(FreeTier.FreeSmartAlbums + 4, true))
    }

    @Test
    fun onlyTheTwoWorkDoingCleanupCategoriesAreLocked() {
        listOf(
            CleanupAnalyzer.Category.BURSTS,
            CleanupAnalyzer.Category.NSFW
        ).forEach { assertTrue("$it is paid", FreeTier.cleanupLocked(it, false)) }
        // Compression is metered rather than locked, so its tile has to open for a free user, who then
        // meets the batch cap inside it instead of at the door.
        assertFalse(FreeTier.cleanupLocked(CleanupAnalyzer.Category.COMPRESSIBLE, false))
        CleanupAnalyzer.Category.entries.filterNot { it in FreeTier.PaidCleanupCategories }.forEach {
            assertFalse("$it stays free", FreeTier.cleanupLocked(it, false))
        }
        CleanupAnalyzer.Category.entries.forEach { assertFalse(FreeTier.cleanupLocked(it, true)) }
    }

    @Test
    fun aFreeCompressionRunAllowsOnePhotoAndBlocksBatches() {
        assertFalse(FreeTier.compressionBlocked(0, false))
        assertFalse(FreeTier.compressionBlocked(1, false))
        assertTrue(FreeTier.compressionBlocked(2, false))
        assertTrue(FreeTier.compressionBlocked(40, false))
        assertFalse(FreeTier.compressionBlocked(40, true))
    }
}
