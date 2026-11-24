package com.devomind.gallerysearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What back means while the Safe is rewriting its archive.
 *
 * An import copies the vault, adds the photos to the copy and replaces the original — a blocking
 * write that does not observe coroutine cancellation. Leaving mid-write therefore does not stop it:
 * it only throws away the result, so the photos land in the vault *and* stay in the gallery, and a
 * second import can start on a vault the first one is still writing. The rule these assert: back
 * during a write parks the exit, and the write's own completion closes the screen.
 */
class SafeLeaveGateTest {

    @Test
    fun aBackPressWithNothingWritingClosesTheScreen() {
        assertTrue(SafeLeaveGate().requestLeave())
    }

    @Test
    fun aBackPressDuringAWriteWaitsForTheWrite() {
        val gate = SafeLeaveGate()
        gate.begin()

        assertFalse("the archive is still being rewritten", gate.requestLeave())
        assertTrue("leaving is the write's last act, not the user's", gate.settle())
    }

    @Test
    fun overlappingWritesHoldTheExitUntilTheLastOneSettles() {
        val gate = SafeLeaveGate()
        gate.begin()
        gate.begin()

        assertFalse(gate.requestLeave())
        assertFalse("one write finishing is not the end of the work", gate.settle())
        assertTrue(gate.settle())
    }

    @Test
    fun aWriteNobodyAskedToLeaveForDoesNotCloseTheScreen() {
        val gate = SafeLeaveGate()
        gate.begin()

        assertFalse(gate.settle())
        assertTrue("no exit was parked, so the screen stays", gate.requestLeave())
    }

    @Test
    fun aParkedExitIsSpentOnlyOnce() {
        val gate = SafeLeaveGate()
        gate.begin()
        gate.requestLeave()
        assertTrue(gate.settle())

        gate.begin()
        assertFalse("the exit was already granted", gate.settle())
    }
}
