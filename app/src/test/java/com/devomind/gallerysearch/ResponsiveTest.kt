package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window-shape arithmetic the whole landscape pass hangs on, held apart from Android because every
 * one of these decisions is a boundary, and a boundary that is wrong by one dp is a screen of oversized
 * tiles or a card row with a hole in it — visible on a phone, invisible in a layout preview.
 *
 * The numbers are real device shapes: a 360x800 dp phone is the app's own baseline, and the ratios
 * below are what that phone reports standing up and sideways.
 */
class ResponsiveTest {

    private val portraitWidth = 360

    @Test
    fun aPortraitWindowGetsExactlyTheColumnsTheUserChose() {
        // The whole point of scaling from `smallestScreenWidthDp`: standing up, nothing changes.
        for (columns in DesignTokens.GRID_MIN_COLUMNS..DesignTokens.GRID_MAX_COLUMNS) {
            assertEquals(
                "portrait must not reinterpret the preference",
                columns,
                Responsive.gridColumnsFor(portraitWidth, portraitWidth, columns)
            )
        }
    }

    @Test
    fun aSidewaysPhoneGetsMoreTilesOfTheSameSize() {
        // 800/360 = 2.22, so three 120dp columns become seven ~114dp ones: more across, same tiles.
        val columns = Responsive.gridColumnsFor(800, portraitWidth, 3)
        assertEquals(7, columns)
        assertTrue("tiles must not grow sideways", 800f / columns <= portraitWidth.toFloat() / 3 + 1f)
        // A five-column preference (small tiles) stays small rather than collapsing back to huge.
        assertEquals(11, Responsive.gridColumnsFor(800, portraitWidth, 5))
    }

    @Test
    fun theColumnCountIsCappedForAbsurdlyWideWindows() {
        // A tablet sideways, or a desktop window: the cap keeps one tile from becoming a pixel.
        assertEquals(Responsive.MAX_GRID_COLUMNS, Responsive.gridColumnsFor(1600, portraitWidth, 6))
    }

    @Test
    fun aWindowNarrowerThanTheReferenceLosesNoColumns() {
        // Split-screen at 240dp: never fewer columns than asked, and never zero or negative.
        assertEquals(3, Responsive.gridColumnsFor(240, portraitWidth, 3))
    }

    @Test
    fun aMissingReferenceWidthFallsBackToOne() {
        // smallestScreenWidthDp is 0 before a window exists; dividing by it must not invent density.
        assertEquals(4, Responsive.gridColumnsFor(0, 0, 4))
    }

    @Test
    fun aJustifiedRowStopsGrowingItsTilesOncePastTheReferenceWidth() {
        val reference = 1080  // px at 360dp on a 3x phone
        assertEquals(1080, Responsive.collageExtentWidthPxFor(1080, reference))
        // Sideways the row is 2400px wide, but a tile's extent is still the portrait one — so the
        // width buys two more images per row instead of one enormous one.
        assertEquals(1080, Responsive.collageExtentWidthPxFor(2400, reference))
        // A window narrower than the reference (split screen) still lays out to its own width.
        assertEquals(600, Responsive.collageExtentWidthPxFor(600, reference))
    }

    @Test
    fun aBodyFitsTheWindowWhenTheWindowIsShorterThanTheBodyWants() {
        val density = 2.625f
        val wanted = (360 * density).toInt()  // the fixed cap the layouts used before this existed
        // Standing up: 360dp of body in an 800dp window is affordable, and stays 360dp.
        assertEquals(wanted, Responsive.bodyHeightPxFor(800, density, wanted, 0.72f))
        // Sideways: 72dp of body in a 360dp window is what is left after title, buttons and scrim.
        val sideways = Responsive.bodyHeightPxFor(360, density, wanted, 0.72f)
        assertTrue("the body must not exceed the window", sideways < wanted)
        assertTrue("and must not be a sliver: $sideways", sideways > (150 * density).toInt())
    }

    @Test
    fun cardsKeepTheirWidthAndMultiplyAcrossInsteadOfGrowing() {
        // A card is sized in tiles at the density the user chose, so the live canvas is what changes.
        assertEquals(1, Responsive.albumCardSpan(3))
        assertEquals(2, Responsive.albumCardSpan(4))
        assertEquals(3, Responsive.albumCardSpan(6))

        // Portrait, three columns: one span per card, three across. Sideways, seven: still one span
        // each, so the row gains four cards rather than making every one of them enormous.
        assertEquals(1, Responsive.cardSpan(3, 3 / Responsive.albumCardSpan(3)))
        assertEquals(1, Responsive.cardSpan(7, 7 / Responsive.albumCardSpan(3)))
        // The collage canvas is 1080 spans wide whatever the window, so the card scales from there too.
        assertEquals(360, Responsive.cardSpan(1080, 3))
        assertEquals(154, Responsive.cardSpan(1080, 7))
        // Degenerate inputs must not produce a zero span, which GridLayoutManager treats as a crash.
        assertEquals(1, Responsive.cardSpan(1, 4))
        assertEquals(1, Responsive.cardSpan(0, 0))
    }

    @Test
    fun aShortWindowTrimsItsTitleAndATallOneDoesNot() {        val hero = DesignTokens.HEADER_TITLE_SIZE
        assertEquals(hero, Responsive.titleTextSpFor(800, hero), 0f)
        val trimmed = Responsive.titleTextSpFor(360, hero)
        assertTrue("the title must give height back to the content", trimmed < hero)
        assertTrue("and still be a title, not a caption: $trimmed", trimmed >= 20f)
    }

    @Test
    fun aSquareTileIsASixthOfTheWidthItSharesWithFiveOthers() {
        // Bin and Safe draw fixed-column squares, so their side comes from the measured width — a
        // sideways window has to make them taller too, or the row outgrows the screen that shows it.
        assertEquals(120, Responsive.tileSizePx(360, 3))
        assertEquals(266, Responsive.tileSizePx(800, 3))
        // Zero columns is what a pre-layout view reports: one tile across whatever width there is,
        // and never a side of zero, which would be a photo nobody can see or tap.
        assertEquals(1, Responsive.tileSizePx(0, 0))
        assertEquals(120, Responsive.tileSizePx(120, 0))
    }

    @Test
    fun aCardWithContentOfItsOwnIsCountedByWhatFitsNotByARatio() {
        // A People card needs its 92dp cover plus a label; spreading three of them over a desktop
        // window is fine, spreading twelve is a face two pixels wide.
        val minCard = 112
        assertEquals(3, Responsive.cardsFitting(360, minCard, 6))
        assertEquals(6, Responsive.cardsFitting(800, minCard, 6))
        assertEquals(6, Responsive.cardsFitting(2400, minCard, 6))
        // A window narrower than one card still draws one card, and nonsense inputs stay in range.
        assertEquals(1, Responsive.cardsFitting(80, minCard, 6))
        assertEquals(1, Responsive.cardsFitting(0, 0, 0))
    }
}
