package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {
    @Test
    fun systemAccentIsDeclaredLastSoTheFixedSwatchesKeepTheirOrder() {
        assertEquals(
            listOf("azure", "violet", "teal", "emerald", "amber", "rose", "system"),
            AccentPalette.choices.map { it.key }
        )
    }

    @Test
    fun storedKeyStillResolvesToTheSystemChoice() {
        assertEquals(AccentPalette.Choice.SYSTEM, AccentPalette.Choice.fromKey("system"))
    }

    @Test
    fun onlyAndroid12AndNewerDefineTheDynamicTones() {
        assertFalse(AccentPalette.systemAccentSupported(30))
        assertTrue(AccentPalette.systemAccentSupported(31))
        assertTrue(AccentPalette.systemAccentSupported(35))
    }

    @Test
    fun anUnresolvedOrTransparentToneIsNotAnAccent() {
        // A missing resource surfaces as 0 (transparent black) rather than throwing on some builds.
        assertNull(AccentPalette.usableAccent(0))
        assertNull(AccentPalette.usableAccent(0x803B9EFF.toInt()))
        assertEquals(0xFF3B9EFF.toInt(), AccentPalette.usableAccent(0xFF3B9EFF.toInt()))
    }

    @Test
    fun aSystemKeyOnADeviceWithoutTheToneFallsBackToTheAppDefault() {
        assertEquals(AccentPalette.Choice.AZURE, AccentPalette.resolveChoice("system", false))
        assertEquals(AccentPalette.Choice.SYSTEM, AccentPalette.resolveChoice("system", true))
    }

    @Test
    fun theOtherChoicesSurviveTheAvailabilityVeto() {
        assertEquals(AccentPalette.Choice.ROSE, AccentPalette.resolveChoice("rose", false))
        assertEquals(AccentPalette.Choice.AZURE, AccentPalette.resolveChoice(null, false))
    }
}
