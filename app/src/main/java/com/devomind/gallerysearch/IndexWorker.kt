package com.devomind.gallerysearch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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

    override suspend fun doWork(): Result {
        if (IndexPreferences.isIndexPaused(applicationContext)) {
            showPausedNotification(applicationContext)
            return Result.success()
        }

        setForeground(createForegroundInfo(0, 1))

        return try {
            val imageEncoder = (applicationContext as GallerySearchApp).sharedEncoders.getImageEncoder()
            val repository = GalleryRepository(applicationContext, imageEncoder, null)

            val selected = inputData.getStringArray(SelectedAlbumIdsKey)?.toSet() ?: emptySet()
            val since = IndexPreferences.loadLastIndexedTime(applicationContext)
            val uris = if (since > 0L) {
                repository.getNewImageUris(selected, since)
            } else {
                repository.getImageUrisForAlbumIds(selected)
            }
            if (uris.isEmpty()) {
                IndexPreferences.saveLastIndexedTime(applicationContext)
                return Result.success()
            }
            val total = max(1, uris.size)

            repository.buildIndex(uris) { current, _ ->
                if (IndexPreferences.isIndexPaused(applicationContext)) {
                    throw IndexPausedException()
                }
                val bounded = current.coerceAtMost(total)
                val progressPercent = (bounded * 100) / total
                setProgressAsync(
                    androidx.work.Data.Builder()
                        .putInt(ProgressCurrentKey, bounded)
                        .putInt(ProgressTotalKey, total)
                        .putInt(ProgressPercentKey, progressPercent)
                        .build()
                )
                if (shouldRefreshForeground(bounded, progressPercent, total)) {
                    setForegroundAsync(createForegroundInfo(bounded, total))
                }
            }
            if (IndexPreferences.isIndexPaused(applicationContext)) {
                throw IndexPausedException()
            }

            val allImages = repository.getImageItemsForAlbumIds(emptySet())
            repository.rebuildMetadataIndex(allImages)

            // Save timestamp so next run only processes new photos
            IndexPreferences.saveLastIndexedTime(applicationContext)

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
        val notification: Notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setContentTitle("Indexing gallery photos")
            .setContentText("Processed $current / $total")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, total <= 1)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                IndexControlReceiver.pendingIntent(applicationContext, IndexControlReceiver.ActionPause)
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
        const val SelectedAlbumIdsKey = "selected_album_ids"
        const val ProgressCurrentKey = "progress_current"
        const val ProgressTotalKey = "progress_total"
        const val ProgressPercentKey = "progress_percent"
        private const val Tag = "IndexWorker"
        private const val MaxRetryCount = 3
        private const val ChannelId = "gallery_index_channel"
        private const val NotificationId = 1001
        private const val PausedNotificationId = 1002
        private const val ForegroundItemStep = 4
        private const val ForegroundPercentStep = 1

        fun showPausedNotification(context: Context) {
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, ChannelId)
                .setContentTitle("Gallery indexing paused")
                .setContentText("Resume when you are ready to continue semantic search indexing.")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    IndexControlReceiver.pendingIntent(context, IndexControlReceiver.ActionResume)
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
