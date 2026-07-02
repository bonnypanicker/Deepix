package com.devomind.gallerysearch

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager

/**
 * Single source of truth for the three indexing lifecycle actions, shared by the notification
 * actions ([IndexControlReceiver]), the Settings screen, and the side drawer:
 *
 * - [pause]  — halt now, keep a persistent "paused" notification so the user can resume from the panel.
 * - [resume] — continue where indexing left off (already-embedded photos are skipped).
 * - [stop]   — halt now and remove every indexing notification from the panel.
 * - [start]  — begin/refresh indexing (grants consent).
 *
 * All routes honour the "index only while charging" preference via [IndexWorker.buildWorkRequest].
 */
object IndexController {

    fun pause(context: Context) {
        IndexPreferences.setIndexStopped(context, false)
        IndexPreferences.setIndexPaused(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(IndexWorker.WorkName)
        IndexWorker.showPausedNotification(context)
    }

    fun resume(context: Context) {
        IndexPreferences.setIndexStopped(context, false)
        IndexPreferences.setIndexPaused(context, false)
        IndexWorker.cancelStatusNotification(context)
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    /**
     * Halt indexing and clear the notification. Uses a distinct "stopped" flag (paused stays false)
     * so the cancelled worker's teardown never re-posts a paused notification, while browse/relaunch
     * still won't silently restart it.
     */
    fun stop(context: Context) {
        IndexPreferences.setIndexPaused(context, false)
        IndexPreferences.setIndexStopped(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(IndexWorker.WorkName)
        IndexWorker.cancelStatusNotification(context)
    }

    fun start(context: Context) {
        IndexPreferences.setIndexStopped(context, false)
        IndexPreferences.setIndexPaused(context, false)
        IndexPreferences.setIndexConsentGiven(context, true)
        IndexWorker.cancelStatusNotification(context)
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val selection = IndexPreferences.loadSelectedAlbums(context)
        WorkManager.getInstance(context).enqueueUniqueWork(
            IndexWorker.WorkName,
            policy,
            IndexWorker.buildWorkRequest(context, selection)
        )
    }
}
