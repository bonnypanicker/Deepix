package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The rolling-window arithmetic that decides whether a pass may hold a foreground service.
 *
 * Getting this wrong in either direction is user-visible: too generous and Android 15 kills the process
 * at the `dataSync` cap, too strict and a first pass on a large library defers itself for days. Both are
 * one-boundary mistakes, which is why the window edges are pinned explicitly rather than sampled.
 */
class ForegroundBudgetTest {

    /** An arbitrary hour index with a non-zero offset inside it, so hour rounding is exercised. */
    private val now = TimeUnit.HOURS.toMillis(480_000L) + 1_234L

    private val nowHour get() = ForegroundBudget.hourIndex(now)

    private fun hours(count: Long) = TimeUnit.HOURS.toMillis(count)

    private fun bucketed(offsetHours: Long, used: Long) = mapOf(nowHour + offsetHours to used)

    @Test
    fun theAllowanceIsTheSixHoursThePlatformGrants() {
        // Asserted against TimeUnit rather than the same multiplication that produced it: a dropped
        // factor in a constant like this reads as a plausible number and costs a night of photos.
        assertEquals("the dataSync cap is not six hours", hours(6), ForegroundBudget.CapMillis)
        assertTrue(
            "the stop reserve has to leave room for a batch in flight",
            ForegroundBudget.StopReserveMillis < ForegroundBudget.CapMillis / 4
        )
        assertEquals(24L, ForegroundBudget.WindowHours)
    }

    @Test
    fun anEmptyLedgerHasRoom() {
        assertTrue(ForegroundBudget.hasRoom(emptyMap(), now))
        assertEquals(ForegroundBudget.CapMillis, ForegroundBudget.remainingMillis(emptyMap(), now))
        assertNull(ForegroundBudget.refillAtMillis(emptyMap(), now))
    }

    @Test
    fun usageInsideTheWindowCountsAndLeavesTheRest() {
        val used = hours(2)
        assertTrue(ForegroundBudget.hasRoom(bucketed(-1, used), now))
        assertEquals(ForegroundBudget.CapMillis - used, ForegroundBudget.remainingMillis(bucketed(-1, used), now))
    }

    /** The window edge, pinned from both sides: 23 h of history is still debt, 24 h is not. */
    @Test
    fun onlyTheLastTwentyFourHoursCount() {
        val heavy = hours(6)
        assertFalse(ForegroundBudget.hasRoom(bucketed(-23, heavy), now))
        assertTrue(
            "usage that has aged out of the rolling window is spendable again",
            ForegroundBudget.hasRoom(bucketed(-24, heavy), now)
        )
    }

    @Test
    fun aRecordFromTheFutureIsNotHeldAgainstThePass() {
        // A clock jump forward can leave a bucket stamped ahead of the hour it is read in. Charging the
        // pass for it would defer indexing with nothing spent, and the ledger would never clear.
        assertTrue(ForegroundBudget.hasRoom(bucketed(1, hours(6)), now))
    }

    @Test
    fun theTimeAlreadyRunCountsAgainstTheAllowanceBeforeItIsRecorded() {
        val spent = ForegroundBudget.CapMillis - ForegroundBudget.StopReserveMillis / 2
        assertTrue("without the live segment the pass looks half-finished and safe", ForegroundBudget.hasRoom(emptyMap(), now))
        assertFalse(ForegroundBudget.hasRoom(emptyMap(), now, liveMillis = spent))
        assertEquals(spent, ForegroundBudget.usedMillis(emptyMap(), now, liveMillis = spent))
    }

    @Test
    fun theWaitEndsWhenTheOldestHourAgesOut() {
        val exitHour = ForegroundBudget.refillAtMillis(bucketed(-2, hours(6)), now)
        assertEquals(
            "a whole hour of usage leaves the window one day after it was spent",
            TimeUnit.HOURS.toMillis(nowHour + 22),
            exitHour
        )
    }

    /** One hour exiting is not enough when the debt is spread: the answer is the hour that crosses. */
    @Test
    fun theWaitWalksForwardUntilEnoughRoomOpensUp() {
        val buckets = mapOf(nowHour - 3 to TimeUnit.MINUTES.toMillis(5), nowHour - 2 to hours(6))
        val exitHour = ForegroundBudget.refillAtMillis(buckets, now)
        assertEquals(
            "freeing five minutes from the oldest hour still leaves the cap spent",
            TimeUnit.HOURS.toMillis(nowHour + 22),
            exitHour
        )
    }

    @Test
    fun roomAlreadyAvailableNeedsNoWait() {
        assertNull(ForegroundBudget.refillAtMillis(bucketed(-1, hours(1)), now))
    }

    @Test
    fun theLedgerSurvivesItsOwnTextForm() {
        val buckets = mapOf(nowHour to TimeUnit.MINUTES.toMillis(90), nowHour - 5 to hours(3))
        assertEquals(buckets, ForegroundBudget.decode(ForegroundBudget.encode(buckets)))
    }

    @Test
    fun aCorruptRecordCostsItselfNotTheLedger() {
        val written = "${nowHour}:5400000,${nowHour - 2}:18000000,junk,${nowHour - 4}:notamillis,${nowHour - 7}:0"
        val decoded = ForegroundBudget.decode(written)
        assertEquals(
            "a half-written ledger line must not cost the hours that parsed",
            mapOf(nowHour to 5_400_000L, nowHour - 2 to 18_000_000L),
            decoded
        )
        assertEquals(emptyMap<Long, Long>(), ForegroundBudget.decode(null))
        assertEquals(emptyMap<Long, Long>(), ForegroundBudget.decode(""))
    }

    @Test
    fun zeroSpansAreNotWrittenDown() {
        // A pass that deferred before taking the foreground measures a few milliseconds. Recording those
        // as hours would put debt on an hour that holds none, and the ledger would grow for nothing.
        assertEquals("", ForegroundBudget.encode(mapOf(nowHour to 0L)))
    }
}
