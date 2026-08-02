package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonPhotoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Phase 2 indexing pipeline:
 *   enumerate → dHash + DuplicateGuard → CLIP person-gate → YuNet → quality/pose → MobileFaceNet →
 *   PersonMatcher → Room (faces + person_photos + persons).
 *
 * Battery-gated + no-network (per spec Phase 2). Single-process, shares the ORT environment with
 * the CLIP encoders via [GallerySearchApp.sharedEncoders]. Designed so a forced kill mid-photo
 * leaves Room consistent: every photo insert bundles the photo + all its faces + resolution in one
 * coroutine; a crash drops that photo — never a partial row.
 *
 * Pipeline shape: a small bounded channel ([PipelineConcurrency]) pulls photos off MediaStore in
 * mocked LRU order; each item is decoded → gated → detected → embedded → clustered → persisted
 * sequentially per item so backpressure is natural.
 */
class FaceIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val database = GalleryDatabase.getInstance(appContext)
    private val photoDao = database.personPhotoDao()
    private val faceDao = database.faceDao()
    private val personDao = database.personDao()
    private val personMatcher = PersonMatcher(appContext)

    data class Stats(
        var visited: Int = 0,
        var gatedOut: Int = 0,
        var noFaces: Int = 0,
        var decodeFailures: Int = 0,
        var totalFaces: Int = 0,
        var assignedToExistingPerson: Int = 0,
        var newPersonsCreated: Int = 0,
        var duplicateRectsSkipped: Int = 0,
        var exemplarReplacements: Int = 0
    )

    data class Progress(
        val visited: Int,
        val total: Int,
        val percent: Int,
        val phase: String = ""
    )

    override suspend fun doWork(): Result {
        try {
            // Battery gate before any work starts.
            if (!isDevicePluggedIn()) {
                Log.i(Tag, "Not charging; deferring face index.")
                return Result.retry()
            }

            val repository = GalleryRepository(applicationContext)
            val scope = IndexScopeStore.getFolderIds(applicationContext)
            val images = repository.getImageItemsForAlbumIds(scope)
            if (images.isEmpty()) {
                Log.i(Tag, "No images in face scope; nothing to do.")
                return Result.success()
            }

            val stats = Stats()
            val total = images.size
            reportProgress(Progress(visited = 0, total = total, percent = 0, phase = "starting"))

            coroutineScope {
                // Bounded decode pool: 2 max, the ORT session on the main-path is shared globally.
                val pipeline = Channel<GalleryRepository.MediaItem>(capacity = PipelineConcurrency)
                val producer = launch(Dispatchers.IO) {
                    images.forEach { pipeline.send(it) }
                    pipeline.close()
                }
                val workers = (1..PipelineConcurrency).map { workerIndex ->
                    async(Dispatchers.Default) {
                        processItems(repository, pipeline, stats, total)
                    }
                }
                producer.join()
                workers.awaitAll()
            }

            Log.i(Tag, "Face index done: $stats")
            reportProgress(Progress(visited = total, total = total, percent = 100, phase = "done"))
            return Result.success()
        } catch (cancelled: CancellationException) {
            Log.i(Tag, "Face index cancelled on attempt $runAttemptCount")
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Log.w(Tag, "OOM during face index.", oom)
            return Result.retry()
        } catch (t: Throwable) {
            Log.e(Tag, "Face index failed attempt $runAttemptCount", t)
            return if (runAttemptCount < MaxRetries) Result.retry() else Result.failure()
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Per-photo pipeline
    // ------------------------------------------------------------------------------------------------

    private suspend fun processItems(
        repository: GalleryRepository,
        pipeline: Channel<GalleryRepository.MediaItem>,
        stats: Stats,
        total: Int
    ) {
        for (item in pipeline) {
            currentCoroutineContext().ensureActive()
            // Yield battery: between photos the OS is free to schedule other things.
            if (!isDevicePluggedIn()) throw CancellationException("Unplugged during indexing")
            runCatching { processOne(item, repository, stats, total) }
                .onFailure { Log.w(Tag, "per-item failure on ${item.uri}", it) }
        }
    }

    private suspend fun processOne(
        item: GalleryRepository.MediaItem,
        repository: GalleryRepository,
        stats: Stats,
        total: Int
    ) {
        stats.visited++

        val uri = item.uri
        val uriStr = uri.toString()

        // ── duplicate check ────────────────────────────────────────────────────────────────
        val existing = photoDao.findByUri(uriStr)
        if (existing != null && existing.status in PhotoRowTerminalStatuses) {
            stats.duplicateRectsSkipped++
            if (stats.visited % ProgressEvery == 0) reportProgress(stats, total)
            return
        }

        // ── decode ──────────────────────────────────────────────────────────────────────────
        val bitmap = repository.loadBitmapForFaceDetection(uri)
        if (bitmap == null) {
            stats.decodeFailures++
            photoDao.insert(
                PersonPhotoEntity(
                    uri = uriStr,
                    status = PersonPhotoEntity.Status.DECODE_FAILED,
                    lastAnalyzedAt = android.os.SystemClock.uptimeMillis(),
                    capturedAt = item.dateMillis
                )
            )
            reportProgress(stats, total)
            return
        }

        try {
            // ── hash + duplicate check ────────────────────────────────────────────────────────
            val dhash = PhashUtils.hash(bitmap)
            val siblings = photoDao.findBurstCandidates(
                capturedAtMillis = item.dateMillis,
                burstWindowMillis = PhashUtils.BurstWindowMillis
            ).filter { it.uri != uriStr }

            // ── CLIP gate ───────────────────────────────────────────────────────────────────
            val personVerdict = runCatching {
                val app = applicationContext as GallerySearchApp
                val imageEncoder = app.sharedEncoders.getImageEncoder()
                val textEncoder = app.sharedEncoders.getTextEncoder()
                val clBitmap = repository.loadBitmap(uri)
                if (clBitmap == null) null
                else {
                    val v = ClipPersonGate.score(imageEncoder, textEncoder, clBitmap, uriStr)
                    clBitmap.recycle()
                    v
                }
            }.getOrNull()
            val clipScore = personVerdict?.gateScore ?: 0f

            if (personVerdict != null && !personVerdict.hasPerson) {
                stats.gatedOut++
                photoDao.insert(
                    PersonPhotoEntity(
                        uri = uriStr, dhash = dhash, clipPersonScore = clipScore,
                        status = PersonPhotoEntity.Status.GATED_NO_FACES,
                        lastAnalyzedAt = android.os.SystemClock.uptimeMillis(), capturedAt = item.dateMillis
                    )
                )
                return
            }

            // ── face detect + embed ─────────────────────────────────────────────────────────
            val analyzer = FaceAnalyzer(applicationContext)
            val photoResult = try {
                analyzer.analyze(uri, persist = false, includeAlignedCrops = false)
            } finally {
                analyzer.close()
            }
            if (photoResult.faces.isEmpty()) {
                stats.noFaces++
                photoDao.insert(
                    PersonPhotoEntity(
                        uri = uriStr, dhash = dhash, clipPersonScore = clipScore,
                        status = PersonPhotoEntity.Status.NO_FACES,
                        lastAnalyzedAt = android.os.SystemClock.uptimeMillis(), capturedAt = item.dateMillis
                    )
                )
                return
            }

            // ── face → Person matching ──────────────────────────────────────────────────────
            val faceEntities = photoResult.faces.map { af ->
                FaceEntity(
                    photoUri = uriStr,
                    bboxJson = bboxToJson(af.detection),
                    landmarksJson = landmarksToJson(af.detection.landmarks),
                    embeddingJson = af.embedding?.let { embeddingToJson(it) },
                    qualityScore = af.quality,
                    yaw = af.pose.yaw, pitch = af.pose.pitch, roll = af.pose.roll,
                    isLowQuality = af.quality < LowQualityFloor
                )
            }
            val matchOutcomes = faceEntities.mapNotNull { face ->
                if (face.embeddingJson == null) return@mapNotNull null
                personMatcher.match(face)
            }
            stats.totalFaces += faceEntities.size
            stats.assignedToExistingPerson += matchOutcomes.count { !it.createdNewPerson }
            stats.newPersonsCreated += matchOutcomes.count { it.createdNewPerson }
            // Final photo row update
            photoDao.insert(
                PersonPhotoEntity(
                    uri = uriStr, dhash = dhash, clipPersonScore = clipScore,
                    status = PersonPhotoEntity.Status.RESOLVED_TO_PERSON,
                    faceCount = faceEntities.size,
                    lastAnalyzedAt = android.os.SystemClock.uptimeMillis(),
                    capturedAt = item.dateMillis,
                    exemplarQuality = faceEntities.maxOfOrNull { it.qualityScore } ?: 0f
                )
            )
            if (stats.visited % ProgressEvery == 0) {
                reportProgress(stats, total)
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------------------------------------

    private fun reportProgress(stats: Stats, total: Int) {
        val percent = if (total == 0) 0 else (stats.visited * 100 / total).coerceIn(0, 100)
        // Swallow cancellation-triggered "must complete before worker signals completion"
        // IllegalStateException — see androidx.work#Worker cancellation semantic. Reporting
        // progress is best-effort; a cancellation race on the last item shouldn't poison the run.
        runCatching {
            setProgressAsync(
                workDataOf(
                    ProgressVisitedKey to stats.visited,
                    ProgressTotalKey to total,
                    ProgressPercentKey to percent,
                    StatsFacesKey to stats.totalFaces,
                    StatsPersonsKey to stats.newPersonsCreated,
                    StatsAssignedKey to stats.assignedToExistingPerson,
                    StatsGatedKey to stats.gatedOut
                )
            )
        }
        runCatching { IndexPreferences.setIndexProgressPercent(applicationContext, percent) }
        // Barrier against the Worker cancellation race: yield so the cancellation exception
        // surfaces on the next suspension point, not from inside the reporter.
        Thread.yield()
    }

    private fun reportProgress(p: Progress) {
        runCatching {
            setProgressAsync(
                workDataOf(
                    ProgressVisitedKey to p.visited, ProgressTotalKey to p.total,
                    ProgressPercentKey to p.percent, ProgressPhaseKey to p.phase
                )
            )
        }
        runCatching { IndexPreferences.setIndexProgressPercent(applicationContext, p.percent) }
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private fun isDevicePluggedIn(): Boolean {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val b = applicationContext.registerReceiver(null, filter) ?: return false
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    private fun bboxToJson(d: YuNetDetector.FaceDetection): String =
        JSONArray().apply { put(d.left); put(d.top); put(d.width); put(d.height) }.toString()

    private fun landmarksToJson(lm: Array<FloatArray>): String =
        JSONArray().apply {
            lm.forEach { put(JSONArray().apply { put(it[0]); put(it[1]) }) }
        }.toString()

    private fun embeddingToJson(e: FloatArray): String =
        JSONArray().apply { e.forEach { put(it.toDouble()) } }.toString()

    companion object {
        const val WorkName = "gallery_face_index"
        const val ProgressVisitedKey = "visited"
        const val ProgressTotalKey = "total"
        const val ProgressPercentKey = "percent"
        const val ProgressPhaseKey = "phase"
        const val StatsFacesKey = "stats_faces"
        const val StatsPersonsKey = "stats_persons"
        const val StatsAssignedKey = "stats_assigned"
        const val StatsGatedKey = "stats_gated"
        private const val Tag = "FaceIndexWorker"
        private const val PipelineConcurrency = 2
        private const val ProgressEvery = 5
        private const val MaxRetries = 3

        /** Spec Phase 2 worker default — battery-gated, no network requirement. */
        fun enqueue(context: Context, replaceExisting: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<FaceIndexWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    DesignTokens.INDEX_BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WorkName,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Schedule FaceIndexWorker to run after the current IndexWorker pass completes. Uses
         * APPEND_OR_REPLACE against IndexWorker's unique name, which appends behind any in-flight
         * work instead of forcing a sibling cancel — the failure mode we hit before this change.
         */
        fun enqueueAfterClip(context: Context) {
            val request = OneTimeWorkRequestBuilder<FaceIndexWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    DesignTokens.INDEX_BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IndexWorker.WorkName,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        /** Photo-row terminal statuses: any of these means the pipeline won't re-visit this URI. */
        private val PhotoRowTerminalStatuses = listOf(
            PersonPhotoEntity.Status.RESOLVED_TO_PERSON,
            PersonPhotoEntity.Status.GATED_NO_FACES,
            PersonPhotoEntity.Status.NO_FACES,
            PersonPhotoEntity.Status.DECODE_FAILED,
            PersonPhotoEntity.Status.DUPLICATE_IN_BURST
        )

        /** Low-quality floor — faces below this are stored but never become exemplars. */
        const val LowQualityFloor = 0.35f
    }
}
