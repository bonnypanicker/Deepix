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
    private val analyzer = FaceAnalyzer(appContext)

    data class Stats(
        var visited: Int = 0,
        var gatedOut: Int = 0,
        var noFaces: Int = 0,
        var decodeFailures: Int = 0,
        var totalFaces: Int = 0,
        var assignedToExistingPerson: Int = 0,
        var newPersonsCreated: Int = 0,
        /** Already carried a terminal row from an earlier run — never entered the pipeline. */
        var alreadyIndexedSkipped: Int = 0,
        /** Entered the pipeline but resolved to an existing burst frame's exemplar. */
        var duplicateRectsSkipped: Int = 0,
        var exemplarReplacements: Int = 0,
        /** CLIP-gate verdicts that reused a stored MobileCLIP embedding (the spec's intended path). */
        var clipReused: Int = 0,
        /** CLIP-gate verdicts that had to re-encode the bitmap (stored embedding missing). */
        var clipReencoded: Int = 0,
        /** ElapsedRealtime at the start of doWork(); used for the throughput SLA report. */
        var startedAtMs: Long = 0L
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

            val stats = Stats(startedAtMs = android.os.SystemClock.elapsedRealtime())
            val total = images.size
            val mediaByUri = images.associateBy { it.uri.toString() }
            // The MobileCLIP image embeddings IndexWorker just persisted — keyed by uri string.
            // Loaded once and reused for every photo's CLIP person-gate so the gate reuses the
            // stored embedding (spec: "reuse existing MobileCLIP-S2 embeddings") instead of
            // re-decoding + re-encoding each photo. Empty when the CLIP pass hasn't run yet; in
            // that case processOne falls back to a live encode of the already-decoded bitmap.
            val clipEmbeddings = runCatching { repository.allEmbeddings() }.getOrDefault(emptyMap())
            if (clipEmbeddings.isEmpty()) {
                Log.w(Tag, "No stored CLIP embeddings found — gate will live-encode each photo.")
            } else {
                Log.i(Tag, "Reusing ${clipEmbeddings.size} stored CLIP embeddings for person-gate.")
            }
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
                        processItems(repository, pipeline, stats, total, mediaByUri, clipEmbeddings)
                    }
                }
                producer.join()
                workers.awaitAll()
            }

            logThroughput(stats)
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
        total: Int,
        mediaByUri: Map<String, GalleryRepository.MediaItem>,
        clipEmbeddings: Map<String, FloatArray>
    ) {
        for (item in pipeline) {
            currentCoroutineContext().ensureActive()
            // Yield battery: between photos the OS is free to schedule other things.
            if (!isDevicePluggedIn()) throw CancellationException("Unplugged during indexing")
            runCatching { processOne(item, repository, stats, total, mediaByUri, clipEmbeddings) }
                .onFailure { Log.w(Tag, "per-item failure on ${item.uri}", it) }
        }
    }

    private suspend fun processOne(
        item: GalleryRepository.MediaItem,
        repository: GalleryRepository,
        stats: Stats,
        total: Int,
        mediaByUri: Map<String, GalleryRepository.MediaItem>,
        clipEmbeddings: Map<String, FloatArray>
    ) {
        updateStats(stats) { visited++ }

        val uri = item.uri
        val uriStr = uri.toString()

        // ── duplicate check ────────────────────────────────────────────────────────────────
        val existing = photoDao.findByUri(uriStr)
        if (existing != null && existing.status in PhotoRowTerminalStatuses) {
            updateStats(stats) { alreadyIndexedSkipped++ }
            if (visitedCount(stats) % ProgressEvery == 0) reportProgress(stats, total)
            return
        }

        // ── decode ──────────────────────────────────────────────────────────────────────────
        val bitmap = repository.loadBitmapForFaceDetection(uri)
        if (bitmap == null) {
            updateStats(stats) { decodeFailures++ }
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
            // Spec: "reuse existing MobileCLIP-S2 embeddings". The IndexWorker pass that just
            // finished already encoded every in-scope photo and persisted it to
            // embedding_index.bin; we reuse that vector instead of re-decoding + re-encoding
            // each photo here. Only when the stored embedding is missing (CLIP pass skipped the
            // uri, e.g. a decode failure upstream) do we fall back to a live encode — and even
            // then we reuse the 2560px face-detection bitmap we already decoded, so there's no
            // second bitmap decode on the hot path.
            val personVerdict = runCatching {
                val app = applicationContext as GallerySearchApp
                val textEncoder = app.sharedEncoders.getTextEncoder()
                val storedEmbedding = clipEmbeddings[uriStr]
                if (storedEmbedding != null) {
                    updateStats(stats) { clipReused++ }
                    ClipPersonGate.scoreEmbedding(textEncoder, storedEmbedding, uriStr)
                } else {
                    updateStats(stats) { clipReencoded++ }
                    val imageEncoder = app.sharedEncoders.getImageEncoder()
                    ClipPersonGate.score(imageEncoder, textEncoder, bitmap, uriStr)
                }
            }.getOrNull()
            val clipScore = personVerdict?.gateScore ?: 0f

            // Grey-zone negatives deliberately reach YuNet: this preserves recall and gives us
            // persisted score/outcome pairs for threshold calibration.
            if (personVerdict != null && !personVerdict.hasPerson && !personVerdict.isGreyZone) {
                updateStats(stats) { gatedOut++ }
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
            // Reuses the bitmap decoded above for the dHash; both want the same
            // loadBitmapForFaceDetection scale, and detection coords land in that space.
            val photoResult = analyzer.analyze(
                uri, persist = false, includeAlignedCrops = false, decoded = bitmap
            )
            if (photoResult.faces.isEmpty()) {
                updateStats(stats) { noFaces++ }
                photoDao.insert(
                    PersonPhotoEntity(
                        uri = uriStr, dhash = dhash, clipPersonScore = clipScore,
                        status = PersonPhotoEntity.Status.NO_FACES,
                        lastAnalyzedAt = android.os.SystemClock.uptimeMillis(), capturedAt = item.dateMillis
                    )
                )
                return
            }

            val candidatePhoto = PersonPhotoEntity(
                uri = uriStr,
                dhash = dhash,
                clipPersonScore = clipScore,
                status = PersonPhotoEntity.Status.HAS_FACES_UNMATCHED,
                faceCount = photoResult.faces.size,
                exemplarQuality = photoResult.faces.maxOfOrNull { it.quality } ?: 0f,
                capturedAt = item.dateMillis,
                lastAnalyzedAt = android.os.SystemClock.uptimeMillis()
            )
            val duplicate = DuplicateGuard.classify(
                DuplicateGuard.Request(
                    candidatePhoto = candidatePhoto,
                    candidateWidth = item.width,
                    candidateHeight = item.height,
                    candidateFaceProportions = photoResult.faces.map { face ->
                    (face.detection.width * face.detection.height) /
                        (item.width.coerceAtLeast(1) * item.height.coerceAtLeast(1)).toFloat()
                    },
                    candidateMaxQuality = candidatePhoto.exemplarQuality,
                    siblingsInWindow = siblings,
                    siblingWidths = siblings.mapNotNull { sibling ->
                    mediaByUri[sibling.uri]?.width?.let { sibling.uri to it }
                    }.toMap(),
                    siblingHeights = siblings.mapNotNull { sibling ->
                    mediaByUri[sibling.uri]?.height?.let { sibling.uri to it }
                    }.toMap(),
                    siblingFaceProportions = siblingFaceProportions(siblings, mediaByUri)
                )
            )
            if (duplicate is DuplicateGuard.Outcome.DuplicateOf &&
                duplicate.contentLikelySameFaces && !duplicate.shouldReplaceExemplar
            ) {
                val exemplarUri = duplicate.incumbentExemplar.exemplarPhotoUri ?: duplicate.incumbentExemplar.uri
                photoDao.insert(
                    candidatePhoto.copy(
                        status = PersonPhotoEntity.Status.DUPLICATE_IN_BURST,
                        exemplarPhotoUri = exemplarUri
                    )
                )
                photoDao.setBurstExemplar(duplicate.burstMemberUris + uriStr, exemplarUri)
                updateStats(stats) { duplicateRectsSkipped++ }
                reportProgress(stats, total)
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
            updateStats(stats) {
                totalFaces += faceEntities.size
                assignedToExistingPerson += matchOutcomes.count { !it.createdNewPerson }
                newPersonsCreated += matchOutcomes.count { it.createdNewPerson }
            }
            // Final photo row update
            val replacesBurstExemplar = duplicate is DuplicateGuard.Outcome.DuplicateOf &&
                duplicate.contentLikelySameFaces && duplicate.shouldReplaceExemplar
            photoDao.insert(
                candidatePhoto.copy(
                    status = PersonPhotoEntity.Status.RESOLVED_TO_PERSON,
                    faceCount = faceEntities.size,
                    exemplarQuality = faceEntities.maxOfOrNull { it.qualityScore } ?: 0f,
                    exemplarPhotoUri = if (replacesBurstExemplar) uriStr else null
                )
            )
            if (replacesBurstExemplar) {
                val burst = duplicate as DuplicateGuard.Outcome.DuplicateOf
                photoDao.setBurstExemplar(burst.burstMemberUris + uriStr, uriStr)
                updateStats(stats) { exemplarReplacements++ }
            }
            if (visitedCount(stats) % ProgressEvery == 0) {
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
        val snapshot = synchronized(stats) { stats.copy() }
        val percent = if (total == 0) 0 else (snapshot.visited * 100 / total).coerceIn(0, 100)
        // Swallow cancellation-triggered "must complete before worker signals completion"
        // IllegalStateException — see androidx.work#Worker cancellation semantic. Reporting
        // progress is best-effort; a cancellation race on the last item shouldn't poison the run.
        runCatching {
            setProgressAsync(
                workDataOf(
                    ProgressVisitedKey to snapshot.visited,
                    ProgressTotalKey to total,
                    ProgressPercentKey to percent,
                    StatsFacesKey to snapshot.totalFaces,
                    StatsPersonsKey to snapshot.newPersonsCreated,
                    StatsAssignedKey to snapshot.assignedToExistingPerson,
                    StatsGatedKey to snapshot.gatedOut,
                    StatsSkippedKey to snapshot.alreadyIndexedSkipped
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

    /**
     * Phase 2 throughput SLA report (spec deliverable #14: "30–60 photos/min while charging").
     * Logs the effective photos/min over the run and flags it against [ThroughputTargetPhotosPerMinute]
     * so a backfill that drifts below the SLA is visible in logcat without a dedicated UI.
     */
    private fun logThroughput(stats: Stats) {
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - stats.startedAtMs
        if (elapsedMs <= 0L || stats.visited <= 0) return
        val photosPerMin = (stats.visited * 60_000f / elapsedMs).toInt()
        val reused = stats.clipReused
        val reencoded = stats.clipReencoded
        val verdict = if (photosPerMin >= ThroughputTargetPhotosPerMinute) "met" else "below target"
        Log.i(
            Tag,
            "Throughput: $photosPerMin photos/min over ${elapsedMs / 1000}s (${stats.visited} photos) " +
                "— SLA ${ThroughputTargetPhotosPerMinute}/min $verdict. " +
                "CLIP gate: $reused reused, $reencoded re-encoded."
        )
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

    private inline fun updateStats(stats: Stats, update: Stats.() -> Unit) {
        synchronized(stats) { stats.update() }
    }

    private fun visitedCount(stats: Stats): Int = synchronized(stats) { stats.visited }

    private suspend fun siblingFaceProportions(
        siblings: List<PersonPhotoEntity>,
        mediaByUri: Map<String, GalleryRepository.MediaItem>
    ): Map<String, List<Float>> = buildMap {
        for (sibling in siblings) {
            val media = mediaByUri[sibling.uri] ?: continue
            val imageArea = (media.width.coerceAtLeast(1) * media.height.coerceAtLeast(1)).toFloat()
            val proportions = faceDao.findByPhoto(sibling.uri).mapNotNull { face ->
                runCatching {
                    val bbox = JSONArray(face.bboxJson)
                    val area = bbox.getDouble(2).toFloat() * bbox.getDouble(3).toFloat()
                    (area / imageArea).takeIf { it in 0f..1f }
                }.getOrNull()
            }
            if (proportions.isNotEmpty()) put(sibling.uri, proportions)
        }
    }

    companion object {
        const val WorkName = "gallery_face_index"
        const val WorkTag = "gallery_face_index_work"
        const val ProgressVisitedKey = "visited"
        const val ProgressTotalKey = "total"
        const val ProgressPercentKey = "percent"
        const val ProgressPhaseKey = "phase"
        const val StatsFacesKey = "stats_faces"
        const val StatsPersonsKey = "stats_persons"
        const val StatsAssignedKey = "stats_assigned"
        const val StatsGatedKey = "stats_gated"
        const val StatsSkippedKey = "stats_skipped"
        private const val Tag = "FaceIndexWorker"
        private const val PipelineConcurrency = 2
        private const val ProgressEvery = 5
        private const val MaxRetries = 3
        /** Phase 2 SLA target — photos/min while charging (spec: 30–60). Used by [logThroughput]. */
        private const val ThroughputTargetPhotosPerMinute = 30

        /** Spec Phase 2 worker default — battery-gated, no network requirement. */
        fun enqueue(context: Context, replaceExisting: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<FaceIndexWorker>()
                .addTag(WorkTag)
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
                .addTag(WorkTag)
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
