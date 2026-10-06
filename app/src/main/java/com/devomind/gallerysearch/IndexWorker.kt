package com.devomind.gallerysearch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.max

class IndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private var cachedDecision: IndexRunDecision? = null
    private var lastProbeAt: Long = 0L

    override suspend fun doWork(): Result {
        val result = try {
            runPass()
        } catch (cancelled: CancellationException) {
            // A cancel normally means a fresher request replaced this one, and the replacement wants
            // the session this pass already paid to build. Keep it cached.
            throw cancelled
        }
        // Every other exit is a wait of minutes-to-hours (charger, thermals, compression) or the end
        // of the job: hand the 72 MB vision session back rather than pinning it for the process.
        (applicationContext as GallerySearchApp).sharedEncoders.releaseImageEncoderAfterIndexing()
        return result
    }

    private suspend fun runPass(): Result {
        // Paused means quiet: no worker run and nothing in the notification panel.
        if (IndexPreferences.isIndexPaused(applicationContext)) {
            return Result.success()
        }

        // Compression takes priority over CLIP encoding: while a batch is mid-flight (prepare,
        // review, commit) this worker waits and retries. Same constraint-wait semantics as the
        // charger gate below — never counts toward MaxRetryCount failure.
        if (CompressionBatchStore.isCompressionActive(applicationContext)) {
            Log.i(Tag, "Compression in progress — deferring index run.")
            IndexPreferences.setLastIndexWaitReason(
                applicationContext,
                IndexWaitReason.WaitingForCompression
            )
            return Result.retry()
        }

        val initialDecision = evaluateHardwarePolicy()
        if (initialDecision is IndexRunDecision.Wait) {
            Log.i(Tag, "Index worker waiting: ${initialDecision.reason}.")
            showWaitingNotificationFor(initialDecision.reason)
            return Result.retry()
        }
        val initialProfile = (initialDecision as IndexRunDecision.Run).profile

        try {
            setForeground(createForegroundInfo())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(Tag, "Foreground service start not allowed; indexing in background.", e)
        }

        // Don't begin an intra-pass face worker here — that creates two in-flight WorkSpecs and
        // the cancellation of one would leak into the other via unique-work-name interaction. The
        // post-pass chain schedules FaceIndexWorker once this CLIP pass *succeeds*.

        return try {
            val (imageEncoder, _) = coroutineScope {
                // Read the vision model bytes in parallel with fetching the fixed ORT thread count
                // from prefs so startup reaches the first indexing pass without benchmark work.
                val modelBytesDeferred = async(Dispatchers.IO) { ImageEncoder.preloadModelBytes(applicationContext) }
                val configuredThreadCount = withContext(Dispatchers.Default) {
                    ThreadBenchmark.getOrBenchmark(applicationContext)
                }

                // Fire-and-forget: warms the text encoder so a search right after a
                // background-only indexing run (e.g. night-charging, app never opened) doesn't
                // pay cold-load latency on the first search — MainActivity.ensureEncodersLoaded()
                // already covers the app-opened case. Never awaited, so it can't delay or fail
                // indexing; safe to overlap with the image encoder load below since SharedEncoders
                // now locks each encoder independently instead of sharing one monitor.
                thread(isDaemon = true) {
                    runCatching {
                        (applicationContext as GallerySearchApp).sharedEncoders.getTextEncoder(configuredThreadCount)
                    }.onFailure { Log.w(Tag, "Background text-encoder warm-up failed (non-fatal)", it) }
                }

                val modelBytes = modelBytesDeferred.await()
                val encoder = (applicationContext as GallerySearchApp).sharedEncoders
                    .getImageEncoder(configuredThreadCount, modelBytes)
                encoder to configuredThreadCount
            }
            val repository = GalleryRepository(
                applicationContext,
                imageEncoder,
                null,
                initialProfile
            )

            // The full set of images currently in scope (empty scope = all folders).
            // buildIndex reconciles the index against this exact set: it encodes newly-added
            // photos and drops embeddings for any photo no longer in scope (e.g. an unchecked
            // folder), so scope changes take effect deterministically.
            val scope = IndexScopeStore.getFolderIds(applicationContext)
            val items = repository.getImageItemsForAlbumIds(scope)
            if (items.isEmpty()) {
                // No images in scope — prune the index to empty, then finish.
                repository.buildIndex(items, onProgress = { _, _ -> })
                IndexPreferences.saveLastIndexedTime(applicationContext)
                IndexPreferences.setIndexProgressPercent(applicationContext, 100)
                return Result.success()
            }
            val total = max(1, items.size)
            val mediaByUri = items.associateBy { it.uri.toString() }
            val faceCandidateQueue = FaceCandidateQueue(applicationContext)
            var encodedAnyThisPass = false
            // The database half of the pass: what each stored embedding was encoded from, and where the
            // signatures for this pass's embeddings go. GalleryRepository holds no database reference,
            // so the worker carries both directions.
            val dbRepository = DbRepository(applicationContext)
            val signatures = dbRepository.embeddingSignatures()

            repository.buildIndex(items, signatures = signatures, onProgress = { current, _ ->
                if (IndexPreferences.isIndexPaused(applicationContext)) {
                    throw IndexPausedException()
                }
                when (val decision = evaluateHardwarePolicy()) {
                    is IndexRunDecision.Wait -> throw IndexWaitingException(decision.reason)
                    // Hand the profile down to the live run: this is the mid-pass step-down the
                    // Wait/Run split used to discard.
                    is IndexRunDecision.Run -> repository.indexRunProfile = decision.profile
                }
                // Compression can start mid-pass (user kicked a batch off from Smart Cleanup):
                // yield now and let the retry reconcile + resume without re-encoding.
                if (CompressionBatchStore.isCompressionActive(applicationContext)) {
                    throw IndexWaitingException(IndexWaitReason.WaitingForCompression)
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
            }, onEmbeddingsStored = { indexed ->
                if (indexed.isNotEmpty()) encodedAnyThisPass = true
                // Signed in the same breath as stored: the record is what tells a later pass that this
                // embedding belongs to the file as it stands right now.
                dbRepository.recordEmbeddingSources(indexed.mapNotNull { mediaByUri[it.uri.toString()] })
                if (faceCandidateQueue.enqueueCandidates(indexed, mediaByUri) > 0) {
                    FaceIndexWorker.enqueueCandidates(applicationContext)
                }
            }, onUnsignedEmbeddings = { unsigned ->
                dbRepository.recordEmbeddingSources(unsigned)
            })
            if (IndexPreferences.isIndexPaused(applicationContext)) {
                throw IndexPausedException()
            }

            val allImages = repository.getImageItemsForAlbumIds(emptySet())
            repository.rebuildMetadataIndex(allImages)

            dbRepository.upsertMedia(allImages)

            // Chain the Phase 2 face-index worker: it runs battery-gated on the same photos CLIP
            // just worked through, so detection has fresh CLIP artifacts to reuse. Uses
            // APPEND_OR_REPLACE on this worker's unique name so it runs *behind* us instead of
            // competing (and never cancels this pass mid-flight).
            FaceIndexWorker.enqueueRemainderAfterClip(applicationContext)

            // Save timestamp so next run only processes new photos
            IndexPreferences.saveLastIndexedTime(applicationContext)
            IndexPreferences.setIndexProgressPercent(applicationContext, 100)

            // A clean pass that actually ran inference shows the batch it used is safe: climb the cap
            // one step. Without this a single long-ago OOM would pin batching low forever.
            if (encodedAnyThisPass) {
                IndexPreferences.healOomBatchCap(
                    applicationContext,
                    BatchSizing.deviceBatchCeiling(applicationContext)
                )
            }

            Result.success()
        } catch (waiting: IndexWaitingException) {
            Log.i(Tag, "Index worker waiting: ${waiting.reason}.")
            showWaitingNotificationFor(waiting.reason)
            Result.retry()
        } catch (paused: IndexPausedException) {
            // Pause clears the panel; resume republishes the running pill when work restarts.
            Log.i(Tag, "Index worker paused.")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Log.w(Tag, "Index worker OOM on attempt $runAttemptCount; reducing batch size.", oom)
            // Ceiling, not override: the retry still steps down with the thermal profile, it just
            // can't go above this. A clean pass later heals it back up one step at a time.
            IndexPreferences.saveOomBatchCap(applicationContext, 2)
            if (runAttemptCount < MaxRetryCount) Result.retry() else Result.failure()
        } catch (error: Throwable) {
            Log.w(Tag, "Index worker failed on attempt $runAttemptCount.", error)
            if (runAttemptCount < MaxRetryCount) Result.retry() else Result.failure()
        }
    }

    private fun evaluateHardwarePolicy(): IndexRunDecision {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedDecision
        // The probe is several binder calls (power-save, idle, interactive, thermal headroom) and it
        // used to run on every flushed batch — thousands of times per pass. Thermal state moves on
        // minute-scale time, so a short cache window cannot miss a real overheat while keeping the
        // calls off the hot path. Pause and compression checks stay per batch.
        if (cached != null && now - lastProbeAt < HardwareProbeIntervalMillis) return cached
        lastProbeAt = now
        val decision = IndexHardwareMonitor.decision(applicationContext).second
        cachedDecision = decision
        if (decision is IndexRunDecision.Run) {
            IndexPreferences.setLastIndexWaitReason(applicationContext, null)
        }
        return decision
    }

    private fun showWaitingNotificationFor(reason: IndexWaitReason) {
        IndexPreferences.setLastIndexWaitReason(applicationContext, reason)
        showWaitingNotification(applicationContext)
    }

    /**
     * Android 14+ requires a concrete foreground-service type at both manifest and runtime.
     * WorkManager's two-argument ForegroundInfo constructor reports `none`, which Android 16
     * rejects with InvalidForegroundServiceTypeException even when its service is declared as
     * dataSync in the manifest.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notification = buildStatusNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationId, notification)
        }
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
        private const val HardwareProbeIntervalMillis = 5_000L
        private const val ChannelId = "gallery_index_channel"
        private const val NotificationId = 1001
        private const val PausedNotificationId = 1002

        /**
         * REPLACE transitions (scope rescan, refresh) leave the cancelled spec in the unique-work
         * list, so `firstOrNull()` can shadow the live pass's state and skip its SUCCEEDED hooks.
         * The current pass's live state always wins over leftovers.
         */
        fun pickRelevantWorkInfo(infos: List<WorkInfo>): WorkInfo? =
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?: infos.firstOrNull {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                }
                ?: infos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
                ?: infos.firstOrNull { it.state == WorkInfo.State.FAILED }
                ?: infos.firstOrNull()

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
                    if (IndexPreferences.isUserIdleRequiredForIndexing(context)) setRequiresDeviceIdle(true)
                    if (IndexPreferences.isBatteryNotLowRequiredForIndexing(context)) {
                        setRequiresBatteryNotLow(true)
                    }
                    if (IndexPreferences.isStorageNotLowRequiredForIndexing(context)) {
                        setRequiresStorageNotLow(true)
                    }
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

        /**
         * Minimal locked pill shown while indexing is in flight (running or waiting for the
         * charger). Paused posts nothing — pause clears the panel, resume brings the pill back
         * when work restarts. No progress details or action buttons — the in-app banner and the
         * Indexing page carry those instead. The notification is ongoing (can't be swiped away)
         * and tapping it opens the Indexing page.
         */
        private fun buildStatusNotification(context: Context): Notification {
            val openIntent = Intent(context, IndexingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(context, ChannelId)
                .setContentTitle("Pixa learning your photos")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .build()
        }

        fun showWaitingNotification(context: Context) {
            ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NotificationId, buildStatusNotification(context))
        }

        /** Clears every indexing pill, including the paused id posted by older app versions. */
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
private class IndexWaitingException(val reason: IndexWaitReason) : RuntimeException()
