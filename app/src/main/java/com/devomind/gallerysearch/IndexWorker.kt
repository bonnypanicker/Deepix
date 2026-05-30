package com.devomind.gallerysearch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlin.math.max

class IndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo(0, 1))

        var imageEncoder: ImageEncoder? = null
        var textEncoder: TextEncoder? = null

        return runCatching {
            val threads = ThreadBenchmark.getOrBenchmark(applicationContext)
            val ep = ExecutionProviderSelector.getOrSelect(applicationContext)
            val cacheDir = applicationContext.cacheDir.absolutePath

            imageEncoder = ImageEncoder(applicationContext, threads, ep, cacheDir)
            textEncoder = TextEncoder(applicationContext, threads, ep, cacheDir)
            val repository = GalleryRepository(applicationContext, imageEncoder!!, textEncoder!!)
            
            GalleryRepository.BatchSize = OnnxSessionOptions.recommendedBatchSize(ep)

            val selected = inputData.getStringArray(SelectedAlbumIdsKey)?.toSet() ?: emptySet()
            val allUris = repository.getImageUrisForAlbumIds(selected)
            
            repository.loadCachedIndexForUris(allUris)
            repository.pruneDeletedImages()
            
            val urisToProcess = repository.getNewImageUris(allUris)
            val total = max(1, urisToProcess.size)

            if (urisToProcess.isNotEmpty()) {
                repository.buildIndex(urisToProcess) { current, _ ->
                    val bounded = current.coerceAtMost(total)
                    val progressPercent = (bounded * 100) / total
                    setProgressAsync(
                        androidx.work.Data.Builder()
                            .putInt(ProgressCurrentKey, bounded)
                            .putInt(ProgressTotalKey, total)
                            .putInt(ProgressPercentKey, progressPercent)
                            .build()
                    )
                    setForegroundAsync(createForegroundInfo(bounded, total))
                }
            }

            // Save timestamp so next run only processes new photos
            IndexPreferences.saveLastIndexedTime(applicationContext)

            Result.success()
        }.getOrElse {
            Result.failure()
        }.also {
            imageEncoder?.close()
            textEncoder?.close()
        }
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
        const val SelectedAlbumIdsKey = "selected_album_ids"
        const val ProgressCurrentKey = "progress_current"
        const val ProgressTotalKey = "progress_total"
        const val ProgressPercentKey = "progress_percent"
        private const val ChannelId = "gallery_index_channel"
        private const val NotificationId = 1001
    }
}
