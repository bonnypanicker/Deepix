package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock race the Safe used to have: backgrounding the activity during an import ran `lock()`,
 * which wiped the plaintext staging directory the import was writing through, and the archive append
 * landed short. These assert the deferral itself — who performs the lock, and when.
 */
class SafeWorkGuardTest {

    @Test
    fun aLockAskedForWhileWorkRunsIsHandedToTheWorker() {
        val guard = SafeWorkGuard()
        guard.begin()

        assertFalse("the caller must not lock under a running write", guard.requestLock())
        assertTrue(guard.end())
    }

    @Test
    fun aLockAskedForWhileIdleIsPerformedAtOnce() {
        val guard = SafeWorkGuard()

        assertTrue(guard.requestLock())
        // Nothing was in flight, so no worker owes the lock afterwards.
        guard.begin()
        assertFalse(guard.end())
    }

    @Test
    fun onlyTheLastWriterStandingOwesTheLock() {
        val guard = SafeWorkGuard()
        guard.begin()
        guard.begin()

        assertFalse(guard.requestLock())
        assertFalse("a second write is still running", guard.end())
        assertTrue(guard.isBusy)
        assertTrue(guard.end())
        assertFalse(guard.isBusy)
    }

    @Test
    fun aSecondUnlockForgetsTheParkedLock() {
        val guard = SafeWorkGuard()
        guard.begin()
        assertFalse(guard.requestLock())

        guard.cancelPendingLock()

        assertFalse("the user is back inside the vault; no lock is owed", guard.end())
    }

    @Test
    fun aLockAskedForTwiceIsStillOneLock() {
        val guard = SafeWorkGuard()
        guard.begin()
        assertFalse(guard.requestLock())
        assertFalse(guard.requestLock())

        assertTrue(guard.end())
        // The request was consumed by the worker that finished; the next caller locks normally.
        assertTrue(guard.requestLock())
    }

    @Test
    fun endingUnbalancedWorkIsAProgrammingError() {
        val guard = SafeWorkGuard()
        guard.begin()
        assertFalse("nothing asked for a lock, so no worker duty is owed", guard.end())

        val failure = runCatching { guard.end() }.exceptionOrNull()
        assertTrue("expected check(), got $failure", failure is IllegalStateException)
        assertEquals("end() called with no work in flight", failure?.message)
    }
}
