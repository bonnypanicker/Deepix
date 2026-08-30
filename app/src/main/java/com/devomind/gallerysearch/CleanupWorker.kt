package com.devomind.gallerysearch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Runs the full Smart Cleanup analysis in the background (parallel to [IndexWorker]) and persists
 * results to [CleanupResultStore] incrementally so the screen can show and grow them live.
 *
 * Reuses the existing CLIP embeddings + text encoder; never touches the image encoder or indexing.
 */
class CleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val store = CleanupResultStore(appContext)
    private var lastWriteAt = 0L

    override suspend fun doWork(): Result {
        if (IndexPreferences.isCleanupPaused(applicationContext)) {
            return Result.success()
        }
        runCatching { setForeground(foregroundInfo(0, 0)) }
            .onFailure { Log.w(Tag, "Foreground start not allowed; cleanup runs in background.", it) }

        return try {
            val app = applicationContext
            val textEncoder = runCatching {
                (app as GallerySearchApp).sharedEncoders.getTextEncoder()
            }.getOrNull()
            val nsfwClassifier = textEncoder?.let { encoder -> runCatching { NsfwClassifier(encoder) }.getOrNull() }
            val repo = GalleryRepository(app, null, textEncoder)

            val images = repo.getImageItemsForAlbumIds(emptySet())
            if (images.isEmpty()) {
                store.save(emptyResult(complete = true))
                return Result.success()
            }
            val embeddings = repo.allEmbeddings()
            val sizes = loadImageSizes()

            // Resume from any prior run: keep quality findings, skip already-scanned photos.
            val prior = store.load()
            val scanned = LinkedHashSet<String>(prior?.scannedUris ?: emptyList())
            val resumeQuality = if (prior != null) {
                mapOf(
                    CleanupAnalyzer.Category.BLURRY to prior.categoryUris[CleanupAnalyzer.Category.BLURRY].orEmpty().toSet(),
                    CleanupAnalyzer.Category.DARK to prior.categoryUris[CleanupAnalyzer.Category.DARK].orEmpty().toSet(),
                    CleanupAnalyzer.Category.BRIGHT to prior.categoryUris[CleanupAnalyzer.Category.BRIGHT].orEmpty().toSet(),
                    CleanupAnalyzer.Category.LOW_RESOLUTION to prior.categoryUris[CleanupAnalyzer.Category.LOW_RESOLUTION].orEmpty().toSet()
                )
            } else {
                emptyMap()
            }

            var lastDone = scanned.size
            var lastTotal = 0
            val finalReport = CleanupAnalyzer.analyze(
                items = images,
                embeddings = embeddings,
                sizeByUri = sizes,
                encodeText = { runCatching { repo.encodeText(it) }.getOrNull() },
                nsfwMargin = { embedding -> nsfwClassifier?.marginScore(embedding) },
                imageStats = { computeImageStats(it) },
                onProgress = { done, total ->
                    lastDone = done
                    lastTotal = total
                    setProgressAsync(
                        Data.Builder()
                            .putInt(ProgressCurrentKey, done)
                            .putInt(ProgressTotalKey, total)
                            .build()
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { setForegroundAsync(foregroundInfo(done, total)) }
                    }
                },
                onPartial = { report -> persist(report, complete = false, scanned.toList(), lastDone, lastTotal, throttle = true) },
                resumeQuality = resumeQuality,
                scannedUris = scanned
            )

            persist(finalReport, complete = true, scanned.toList(), lastTotal, lastTotal, throttle = false)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Log.w(Tag, "Cleanup worker ran out of memory.", oom)
            Result.failure()
        } catch (error: Throwable) {
            Log.w(Tag, "Cleanup worker failed.", error)
            Result.failure()
        }
    }

    private fun persist(
        report: CleanupAnalyzer.Report,
        complete: Boolean,
        scannedUris: List<String>,
        done: Int,
        total: Int,
        throttle: Boolean
    ) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastWriteAt < WRITE_THROTTLE_MS) return
        lastWriteAt = now
        store.save(
            CleanupResultStore.Result(
                categoryUris = report.categoryItems.mapValues { entry -> entry.value.map { it.uri.toString() } },
                suggestedUris = report.suggestedDeleteUris.mapValues { entry -> entry.value.map { it.toString() } },
                scannedUris = scannedUris,
                done = done,
                total = total,
                complete = complete,
                updatedAt = now
            )
        )
    }

    private fun emptyResult(complete: Boolean) = CleanupResultStore.Result(
        categoryUris = emptyMap(),
        suggestedUris = emptyMap(),
        scannedUris = emptyList(),
        done = 0,
        total = 0,
        complete = complete,
        updatedAt = System.currentTimeMillis()
    )

    private fun loadImageSizes(): Map<String, Long> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE)
        val map = HashMap<String, Long>()
        runCatching {
            applicationContext.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val size = cursor.getLong(sizeCol)
                    map[android.content.ContentUris.withAppendedId(collection, id).toString()] = size
                }
            }
        }.onFailure { Log.w(Tag, "Unable to read image sizes.", it) }
        return map
    }

    private fun computeImageStats(uri: Uri): CleanupAnalyzer.ImageStats? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            applicationContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null
            val target = 128
            var sample = 1
            while (srcW / (sample * 2) >= target && srcH / (sample * 2) >= target) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = applicationContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val stats = imageStatsOf(bmp)
            bmp.recycle()
            stats
        }.getOrNull()
    }

    private fun imageStatsOf(bmp: Bitmap): CleanupAnalyzer.ImageStats {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return CleanupAnalyzer.ImageStats(Float.MAX_VALUE, 128f, 0f)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        var lumaSum = 0L
        var nearWhite = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            gray[i] = lum
            lumaSum += lum
            if (lum >= 245) nearWhite++
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val lap = 4 * gray[idx] - gray[idx - 1] - gray[idx + 1] - gray[idx - w] - gray[idx + w]
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        val variance = if (n == 0) Float.MAX_VALUE else {
            val mean = sum / n
            (sumSq / n - mean * mean).toFloat()
        }
        return CleanupAnalyzer.ImageStats(variance, lumaSum.toFloat() / pixels.size, nearWhite.toFloat() / pixels.size)
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        ensureChannel()
        val text = if (total > 0) "Scanning $done / $total" else "Analyzing your library…"
        val notification: Notification = NotificationCompat.Builder(applicationContext, ChannelId)
            .setContentTitle("Smart cleanup analysis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationId, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Smart cleanup", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val WorkName = "gallery_smart_cleanup"
        const val ProgressCurrentKey = "cleanup_progress_current"
        const val ProgressTotalKey = "cleanup_progress_total"
        private const val Tag = "CleanupWorker"
        private const val ChannelId = "gallery_cleanup_channel"
        private const val NotificationId = 1003
        private const val WRITE_THROTTLE_MS = 1500L
    }
}
