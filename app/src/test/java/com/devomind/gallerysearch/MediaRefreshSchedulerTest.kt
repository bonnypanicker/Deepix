package com.devomind.gallerysearch

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRefreshSchedulerTest {
    @Test
    fun insertUpdateAndDeleteReachDisplayedSnapshot() = runTest {
        val source = linkedMapOf("original.jpg" to 100L)
        var displayed = source.toMap()
        var refreshes = 0
        val scheduler = MediaRefreshScheduler(this) {
            displayed = source.toMap()
            refreshes++
        }
        scheduler.onResume()
        scheduler.onReady()

        source["compressed.heic"] = 40L
        scheduler.onMediaChanged()
        advanceUntilIdle()
        assertEquals(source, displayed)

        source["compressed.heic"] = 35L
        scheduler.onMediaChanged()
        advanceUntilIdle()
        assertEquals(35L, displayed["compressed.heic"])

        source.remove("original.jpg")
        scheduler.onMediaChanged()
        advanceUntilIdle()
        assertEquals(mapOf("compressed.heic" to 35L), displayed)
        assertEquals(3, refreshes)
    }

    @Test
    fun changesWhilePausedRefreshOnlyAfterResume() = runTest {
        var refreshes = 0
        val scheduler = MediaRefreshScheduler(this) { refreshes++ }
        scheduler.onResume()
        scheduler.onReady()
        scheduler.onMediaChanged()
        runCurrent()
        scheduler.onPause()
        advanceUntilIdle()
        assertEquals(0, refreshes)
        scheduler.onMediaChanged()
        advanceUntilIdle()
        assertEquals(0, refreshes)
        scheduler.onResume()
        advanceUntilIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun resumeChecksForMissedNotifications() = runTest {
        var refreshes = 0
        val scheduler = MediaRefreshScheduler(this) { refreshes++ }
        scheduler.onResume()
        scheduler.onReady()
        advanceUntilIdle()
        assertEquals(0, refreshes)
        scheduler.onPause()
        scheduler.onResume()
        advanceUntilIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun burstIsCoalescedAndWaitsForInitialLoad() = runTest {
        var refreshes = 0
        val scheduler = MediaRefreshScheduler(this) { refreshes++ }
        scheduler.onResume()
        repeat(100) { scheduler.onMediaChanged() }
        advanceUntilIdle()
        assertEquals(0, refreshes)
        scheduler.onReady()
        advanceTimeBy(1499)
        assertEquals(0, refreshes)
        advanceUntilIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun notificationDuringQueryTriggersAnotherNonOverlappingRefresh() = runTest {
        var sourceVersion = 1
        var displayedVersion = 0
        var refreshes = 0
        var active = 0
        var maximumActive = 0
        val scheduler = MediaRefreshScheduler(this) {
            active++
            maximumActive = maxOf(maximumActive, active)
            val snapshot = sourceVersion
            delay(2000)
            displayedVersion = snapshot
            refreshes++
            active--
        }
        scheduler.onResume()
        scheduler.onReady()
        scheduler.onMediaChanged()
        advanceTimeBy(1600)
        sourceVersion = 2
        scheduler.onMediaChanged()
        advanceUntilIdle()
        assertEquals(2, displayedVersion)
        assertEquals(2, refreshes)
        assertEquals(1, maximumActive)
    }
}
