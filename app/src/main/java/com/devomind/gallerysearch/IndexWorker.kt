package com.devomind.gallerysearch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlin.math.max

class IndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private var lastForegroundUpdateAt = -1
    private var lastForegroundPercent = -1
    private var foregroundActive = false

    /** True if the device is currently on AC power (the charging constraint is not a runtime gate). */
    private fun isCurrentlyCharging(): Boolean {
        return runCatching {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = applicationContext.registerReceiver(null, filter) ?: return false
            val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }.getOrDefault(false)
    }

    override suspend fun doWork(): Result {
        if (IndexPreferences.isIndexPaused(applicationContext)) {
            showPausedNotification(applicationContext)
            return Result.success()
        }

        // Night-charging-only runtime guard: if the worker fires outside the night window (e.g. a
        // delayed retry), reschedule it for the next window rather than running the heavy scan now.
        if (IndexPreferences.isChargingOnlyIndexing(applicationContext) &&
            IndexPreferences.isNightChargingOnly(applicationContext)
        ) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (!IndexPreferences.isNightChargeHour(hour)) {
                Log.i(Tag, "Outside night charging window — deferring index run.")
                return Result.retry()
            }
        }

        try {
            setForeground(createForegroundInfo(0, 1))
            foregroundActive = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(Tag, "Foreground service start not allowed; indexing in background.", e)
        }

        return try {
            val imageEncoder = (applicationContext as GallerySearchApp).sharedEncoders.getImageEncoder()
            val repository = GalleryRepository(applicationContext, imageEncoder, null)

            // The full set of images currently in scope (empty scope = all folders).
            // buildIndex reconciles the index against this exact set: it encodes newly-added
            // photos and drops embeddings for any photo no longer in scope (e.g. an unchecked
            // folder), so scope changes take effect deterministically.
            val scope = IndexScopeStore.getFolderIds(applicationContext)
            val uris = repository.getImageUrisForAlbumIds(scope)
            if (uris.isEmpty()) {
                // No images in scope — prune the index to empty, then finish.
                repository.buildIndex(uris) { _, _ -> }
                IndexPreferences.saveLastIndexedTime(applicationContext)
                IndexPreferences.setIndexProgressPercent(applicationContext, 100)
                return Result.success()
            }
            val total = max(1, uris.size)

            repository.buildIndex(uris) { current, _ ->
                if (IndexPreferences.isIndexPaused(applicationContext)) {
                    throw IndexPausedException()
                }
                // Honor "index only while charging" at runtime: the WorkManager constraint only gates
                // the *start*, so if the user unplugs mid-scan we pause (not stop) so it resumes from
                // where it left off once plugged in again.
                if (IndexPreferences.isChargingOnlyIndexing(applicationContext) && !isCurrentlyCharging()) {
                    IndexPreferences.setIndexPaused(applicationContext, true)
                    throw IndexPausedException()
                }
                val bounded = current.coerceAtMost(total)
                val progressPercent = (bounded * 100) / total
                IndexPreferences.setIndexProgressPercent(applicationContext, progressPercent)
                setProgressAsync(
                    androidx.work.Data.Builder()
                        .putInt(ProgressCurrentKey, bounded)
                        .putInt(ProgressTotalKey, total)
                        .putInt(ProgressPercentKey, progressPercent)
                        .build()
                )
                if (foregroundActive && shouldRefreshForeground(bounded, progressPercent, total)) {
                    setForegroundAsync(createForegroundInfo(bounded, total))
                }
            }
            if (IndexPreferences.isIndexPaused(applicationContext)) {
                throw IndexPausedException()
            }

            val allImages = repository.getImageItemsForAlbumIds(emptySet())
            repository.rebuildMetadataIndex(allImages)

            val dbRepository = DbRepository(applicationContext)
            dbRepository.upsertMedia(allImages)

            // Save timestamp so next run only processes new photos
            IndexPreferences.saveLastIndexedTime(applicationContext)
            IndexPreferences.setIndexProgressPercent(applicationContext, 100)

            Result.success()
        } catch (paused: IndexPausedException) {
            Log.i(Tag, "Index worker paused by notification action.")
            showPausedNotification(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            if (IndexPreferences.isIndexPaused(applicationContext)) {
                showPausedNotification(applicationContext)
            }
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Log.w(Tag, "Index worker ran out of memory on attempt $runAttemptCount.", oom)
            Result.failure()
        } catch (error: Throwable) {
            Log.w(Tag, "Index worker failed on attempt $runAttemptCount.", error)
            if (runAttemptCount < MaxRetryCount) Result.retry() else Result.failure()
        }
    }

    private fun shouldRefreshForeground(current: Int, progressPercent: Int, total: Int): Boolean {
        if (current <= 1 || current >= total) {
            lastForegroundUpdateAt = current
            lastForegroundPercent = progressPercent
            return true
        }
        val currentStep = current / ForegroundItemStep
        val previousStep = lastForegroundUpdateAt / ForegroundItemStep
        val percentDelta = progressPercent - lastForegroundPercent
        val shouldUpdate = currentStep > previousStep || percentDelta >= ForegroundPercentStep
        if (shouldUpdate) {
            lastForegroundUpdateAt = current
            lastForegroundPercent = progressPercent
        }
        return shouldUpdate
    }

    private fun createForegroundInfo(current: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val indeterminate = total <= 1
        val percent = if (indeterminate) 0 else (current.coerceAtMost(total) * 100) / total
        val text = if (indeterminate) "Starting…" else "$percent%"
        val notification: Notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setContentTitle("Indexing photos for AI search")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                IndexControlReceiver.pendingIntent(applicationContext, IndexControlReceiver.ActionPause)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                IndexControlReceiver.pendingIntent(applicationContext, IndexControlReceiver.ActionStop)
            )
            .build()
        return ForegroundInfo(NotificationId, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ChannelId,
            "Gallery indexing",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val WorkName = "gallery_background_index"
        const val ProgressCurrentKey = "progress_current"
        const val ProgressTotalKey = "progress_total"
        const val ProgressPercentKey = "progress_percent"
        private const val Tag = "IndexWorker"
        private const val MaxRetryCount = 3
        private const val ChannelId = "gallery_index_channel"
        private const val NotificationId = 1001
        private const val PausedNotificationId = 1002
        private const val ForegroundItemStep = 20
        private const val ForegroundPercentStep = 2

        /**
         * Single source of truth for the index work request so every enqueue path
         * (initial start, resume, settings re-apply, notification action) honors the
         * "index only while charging" preference. Reads the pref at build time.
         *
         * When "night charging only" is also on, the request is additionally delayed until the next
         * start of the night window (22:00 device-local) so the heavy scan runs overnight.
         */
        fun buildWorkRequest(
            context: Context,
            initialDelaySeconds: Long = 0
        ): androidx.work.OneTimeWorkRequest {
            val constraints = androidx.work.Constraints.Builder()
                .apply {
                    if (IndexPreferences.isChargingOnlyIndexing(context)) setRequiresCharging(true)
                }
                .build()
            val totalDelaySeconds = maxOf(initialDelaySeconds, nightChargeDelaySeconds(context))
            return androidx.work.OneTimeWorkRequestBuilder<IndexWorker>()
                .setConstraints(constraints)
                // Delay the (heavy) model load + indexing so a cold start renders and becomes
                // interactive first, instead of the worker competing for CPU/RAM during launch.
                // When night-charging-only is on, push the start to the next night window.
                .apply {
                    if (totalDelaySeconds > 0) {
                        setInitialDelay(totalDelaySeconds, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    DesignTokens.INDEX_BACKOFF_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS
                )
                .build()
        }

        /**
         * Seconds until the next start of the night charging window (22:00 device-local), or 0 when
         * night-charging-only is off / charging-only is off / already inside the window.
         */
        private fun nightChargeDelaySeconds(context: Context): Long {
            if (!IndexPreferences.isChargingOnlyIndexing(context)) return 0
            if (!IndexPreferences.isNightChargingOnly(context)) return 0
            val now = java.util.Calendar.getInstance()
            if (IndexPreferences.isNightChargeHour(now.get(java.util.Calendar.HOUR_OF_DAY))) return 0
            // Next 22:00 today (or tomorrow if already past it within the day).
            val nextStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, IndexPreferences.NIGHT_CHARGE_START_HOUR)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
                if (before(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return maxOf(0L, (nextStart.timeInMillis - now.timeInMillis) / 1000)
        }

        fun showPausedNotification(context: Context) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, ChannelId)
                .setContentTitle("Photo indexing paused")
                .setContentText("Resume when you're ready to finish building your AI search index.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    IndexControlReceiver.pendingIntent(context, IndexControlReceiver.ActionResume)
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    IndexControlReceiver.pendingIntent(context, IndexControlReceiver.ActionStop)
                )
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(PausedNotificationId, notification)
        }

        fun cancelStatusNotification(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NotificationId)
            manager.cancel(PausedNotificationId)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                ChannelId,
                "Gallery indexing",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }
}

private class IndexPausedException : RuntimeException()
