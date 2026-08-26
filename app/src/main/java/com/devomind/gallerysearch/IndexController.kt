package com.devomind.gallerysearch

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager

/**
 * Single source of truth for the indexing lifecycle actions, shared by the notification
 * actions ([IndexControlReceiver]), the Settings screen, and the side drawer:
 *
 * - [pause]  — halt now and clear every indexing notification from the panel (paused is quiet).
 * - [resume] — continue where indexing left off; the running pill reappears once work restarts
 *   (already-embedded photos are skipped).
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
        IndexWorker.cancelStatusNotification(context)
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

    /** Indexed-folder scope changed: rebuild against the new scope now (prunes removed, adds new). */
    fun rescan(context: Context) {
        IndexPreferences.setIndexStopped(context, false)
        IndexPreferences.setIndexPaused(context, false)
        IndexPreferences.setIndexConsentGiven(context, true)
        IndexWorker.cancelStatusNotification(context)
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    /**
     * Re-apply current prefs against the existing unique-work entry without forcing a fresh start.
     * Used by constraint toggles (e.g. "index only while charging" / "only at night") where
     * re-enqueuing against a RUNNING job is harmful because [ExistingWorkPolicy.REPLACE] would
     * cancel the active pass. REPLACE is still correct for scope changes via [rescan].
     */
    fun applyPowerConstraintOnly(context: Context) {
        // Don't touch the user-visible pause/stop flags — this is purely a constraint rebuild.
        // If a job is mid-run, ExistingWorkPolicy.UPDATE (the natural replacement) would cancel
        // it. We use KEEP to leave the running job alone; it will pick up the new constraints on
        // its next enqueue, and "waiting for charge" retry is already runtime-checked inside
        // IndexWorker itself.
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IndexWorker.WorkName,
            policy,
            IndexWorker.buildWorkRequest(context)
        )
    }
}
