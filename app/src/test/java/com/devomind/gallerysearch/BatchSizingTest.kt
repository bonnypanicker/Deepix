package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchSizingTest {
    @Test
    fun pauseScalesWithTheBatchThatProducedTheHeat() {
        assertEquals(417L, BatchSizing.pacingDelayMillis(IndexRunProfile.Normal, 1))
        assertEquals(2_502L, BatchSizing.pacingDelayMillis(IndexRunProfile.Normal, 6))
        assertEquals(417L, BatchSizing.pacingDelayMillis(IndexRunProfile.Normal, 0))
    }

    /** The rest a profile asks for is sized to the burst that device class actually produces. */
    @Test
    fun biggestBurstsGetTheLongestRest() {
        val max = BatchSizing.pacingDelayMillis(IndexRunProfile.Max, 10)
        val high = BatchSizing.pacingDelayMillis(IndexRunProfile.High, 8)
        val normal = BatchSizing.pacingDelayMillis(IndexRunProfile.Normal, 6)
        val cooldown = BatchSizing.pacingDelayMillis(IndexRunProfile.Cooldown, 3)
        val quiet = BatchSizing.pacingDelayMillis(IndexRunProfile.Quiet, 2)

        assertEquals(3_000L, max)
        assertEquals(2_800L, high)
        assertEquals(1_800L, cooldown)
        assertEquals(1_000L, quiet)
        assertEquals(true, max > high && high > normal && normal > cooldown && cooldown > quiet)
    }
}
