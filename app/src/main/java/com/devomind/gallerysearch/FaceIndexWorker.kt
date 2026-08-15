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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Phase 2 indexing pipeline:
 *   enumerate → CLIP person-gate → dHash + DuplicateGuard → YuNet → quality/pose → MobileFaceNet →
 *   PersonMatcher → Room (faces + person_photos + persons).
 *
 * Runs only when indexing is allowed by the user's charging preference. Single-process, shares the ORT environment with
 * the CLIP encoders via [GallerySearchApp.sharedEncoders]. Designed so a forced kill mid-photo
 * leaves Room consistent: every photo insert bundles the photo + all its faces + resolution in one
 * coroutine; a crash drops that photo — never a partial row.
 *
 * Pipeline shape: a small bounded channel ([PipelineConcurrency]) pulls photos off MediaStore in
 * mocked LRU order; each item reuses a stored CLIP embedding when possible, falls back to a small
 * decode for gating / dHash, and only then pays the full face-detection decode before clustering
 * and persistence.
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
            // Battery gate before any work starts. On battery-down we don't retry — every
            // photo we'd have written is likewise deferred to a charging plug-in so the OS
            // doesn't see us as a battery vampire.
            if (IndexPreferences.isChargingOnlyIndexing(applicationContext) && !isDevicePluggedIn()) {
                Log.i(Tag, "Charging-only indexing is enabled; deferred face index.")
                return Result.success()
            }

            val repository = GalleryRepository(applicationContext)
            val scope = IndexScopeStore.getFolderIds(applicationContext)
            val images = repository.getImageItemsForAlbumIds(scope)
            if (images.isEmpty()) {
                Log.i(Tag, "No images in face scope; nothing to do.")
                return Result.success()
            }

            // Phase 2 deliverable #12: repair any Face↔vector-index divergence (e.g. a crash
            // between a Room commit and the last vector-index flush) before matching reads the
            // index. Cheap at phone scale; backfills lost vectors from embeddingJson.
            val mode = inputData.getString(ModeKey) ?: Mode.REMAINDER
            if (mode == Mode.REMAINDER) {
                runCatching { FaceIndexConsistency.checkAndRepair(applicationContext) }
                    .onFailure { Log.w(Tag, "Consistency check failed (non-fatal).", it) }
            }

            val released = photoDao.releaseStaleProcessing(
                processingStatus = PersonPhotoEntity.Status.FACE_PROCESSING,
                unprocessedStatus = PersonPhotoEntity.Status.UNPROCESSED,
                olderThan = android.os.SystemClock.uptimeMillis() - StaleClaimMillis
            )
            if (released > 0) Log.w(Tag, "Released $released stale face-processing claims.")
            val stats = Stats(startedAtMs = android.os.SystemClock.elapsedRealtime())
            val mediaByUri = images.associateBy { it.uri.toString() }
            // The MobileCLIP image embeddings IndexWorker just persisted — keyed by uri string.
            // Loaded once and reused for every photo's CLIP person-gate so the gate reuses the
            // stored embedding (spec: "reuse existing MobileCLIP-S2 embeddings") instead of
            // re-decoding + re-encoding each photo. Empty when the CLIP pass hasn't run yet; in
            // that case processOne falls back to a live encode of the already-decoded bitmap.
            val clipEmbeddings = if (mode == Mode.REMAINDER) {
                runCatching { repository.allEmbeddings() }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            if (clipEmbeddings.isEmpty()) {
                Log.w(Tag, "No stored CLIP embeddings found — gate will live-encode each photo.")
            } else {
                Log.i(Tag, "Reusing ${clipEmbeddings.size} stored CLIP embeddings for person-gate.")
            }
            val work = when (mode) {
                Mode.CANDIDATES -> photoDao.findByStatus(
                    PersonPhotoEntity.Status.CLIP_CANDIDATE,
                    CandidateBatchLimit
                ).mapNotNull { row -> mediaByUri[row.uri]?.let { it to row.clipPersonScore } }
                else -> images.map { it to null }
            }.sortedByDescending { it.first.dateMillis }
            if (work.isEmpty()) {
                Log.d(Tag, "No $mode face work is pending.")
                return Result.success()
            }
            val total = work.size
            reportProgress(Progress(visited = 0, total = total, percent = 0, phase = "starting"))

            for ((item, queuedClipScore) in work) {
                currentCoroutineContext().ensureActive()
                if (IndexPreferences.isChargingOnlyIndexing(applicationContext) && !isDevicePluggedIn()) {
                    Log.i(Tag, "Charging-only indexing is enabled; pausing face work.")
                    return Result.retry()
                }
                if (!claim(item, mode)) continue
                try {
                    processOne(item, repository, stats, total, mediaByUri, clipEmbeddings, queuedClipScore)
                } catch (t: Throwable) {
                    photoDao.setStatus(item.uri.toString(), PersonPhotoEntity.Status.UNPROCESSED)
                    Log.w(Tag, "per-item failure on ${item.uri}", t)
                }
                if (mode == Mode.REMAINDER) delay(ResidualPhotoIdleMillis)
            }

            logThroughput(stats)
            Log.i(Tag, "Face index done: $stats")
            // Persist any staged face embeddings to the mmap vector index so the next run /
            // search starts from a consistent on-disk state. (Lost staging on a crash is
            // recovered by FaceIndexConsistency on the next cold start from embeddingJson.)
            runCatching { (applicationContext as GallerySearchApp).faceVectorIndex.flush() }
                .onFailure { Log.w(Tag, "Vector index flush failed.", it) }
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

    private suspend fun processOne(
        item: GalleryRepository.MediaItem,
        repository: GalleryRepository,
        stats: Stats,
        total: Int,
        mediaByUri: Map<String, GalleryRepository.MediaItem>,
        clipEmbeddings: Map<String, FloatArray>,
        queuedClipScore: Float?
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

        var lightweightBitmap: android.graphics.Bitmap? = null
        var faceBitmap: android.graphics.Bitmap? = null
        try {
            // ── CLIP gate ───────────────────────────────────────────────────────────────────
            // Reuse the stored CLIP embedding when available. If not, fall back to a cheap
            // 512px decode for the gate; only photos that survive the gate pay the 2560px
            // face-detection decode.
            val personVerdict = if (queuedClipScore == null) {
                val storedEmbedding = clipEmbeddings[uriStr]
                if (storedEmbedding == null) {
                    photoDao.insert(
                        PersonPhotoEntity(
                            uri = uriStr,
                            status = PersonPhotoEntity.Status.CLIP_UNAVAILABLE,
                            lastAnalyzedAt = android.os.SystemClock.uptimeMillis(),
                            capturedAt = item.dateMillis
                        )
                    )
                    return
                }
                updateStats(stats) { clipReused++ }
                val textEncoder = (applicationContext as GallerySearchApp).sharedEncoders.getTextEncoder()
                ClipPersonGate.scoreEmbedding(textEncoder, storedEmbedding, uriStr)
            } else {
                null
            }
            val clipScore = queuedClipScore ?: personVerdict?.gateScore ?: 0f

            // Grey-zone negatives deliberately reach YuNet: this preserves recall and gives us
            // persisted score/outcome pairs for threshold calibration.
            if (personVerdict != null && !personVerdict.hasPerson && !personVerdict.isGreyZone) {
                updateStats(stats) { gatedOut++ }
                photoDao.insert(
                    PersonPhotoEntity(
                        uri = uriStr, clipPersonScore = clipScore,
                        status = PersonPhotoEntity.Status.GATED_NO_FACES,
                        lastAnalyzedAt = android.os.SystemClock.uptimeMillis(), capturedAt = item.dateMillis
                    )
                )
                return
            }

            // ── lightweight hash + duplicate window lookup ───────────────────────────────────
            val hashBitmap = lightweightBitmap ?: repository.loadBitmap(uri)
            if (hashBitmap == null) {
                updateStats(stats) { decodeFailures++ }
                photoDao.insert(
                    PersonPhotoEntity(
                        uri = uriStr,
                        clipPersonScore = clipScore,
                        status = PersonPhotoEntity.Status.DECODE_FAILED,
                        lastAnalyzedAt = android.os.SystemClock.uptimeMillis(),
                        capturedAt = item.dateMillis
                    )
                )
                reportProgress(stats, total)
                return
            }
            if (lightweightBitmap == null) lightweightBitmap = hashBitmap
            val dhash = PhashUtils.hash(hashBitmap)
            val siblings = photoDao.findBurstCandidates(
                capturedAtMillis = item.dateMillis,
                burstWindowMillis = PhashUtils.BurstWindowMillis
            ).filter { it.uri != uriStr }

            // ── face detect + embed ─────────────────────────────────────────────────────────
            faceBitmap = repository.loadBitmapForFaceDetectionForIndexing(item)
            if (faceBitmap == null) {
                updateStats(stats) { decodeFailures++ }
                photoDao.insert(
                    PersonPhotoEntity(
                        uri = uriStr,
                        dhash = dhash,
                        clipPersonScore = clipScore,
                        status = PersonPhotoEntity.Status.DECODE_FAILED,
                        lastAnalyzedAt = android.os.SystemClock.uptimeMillis(),
                        capturedAt = item.dateMillis
                    )
                )
                reportProgress(stats, total)
                return
            }
            val photoResult = analyzer.analyze(
                uri, persist = false, includeAlignedCrops = false, decoded = faceBitmap
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
            val faceEntities = photoResult.faces
                .sortedByDescending { it.quality }
                .map { af ->
                FaceEntity(
                    photoUri = uriStr,
                    bboxJson = bboxToJson(af.detection),
                    landmarksJson = landmarksToJson(af.detection.landmarks),
                    // Unrecognizable detections stay in Room for diagnostics, but carry no
                    // identity vector and can never create a one-photo Person cluster.
                    embeddingJson = af.embedding?.let { embeddingToJson(it) },
                    // Keep this explicit: the Room entity's SQL default is retained for old
                    // schema compatibility, while new embeddings must carry the live version.
                    embeddingModelVersion = FaceEmbedder.ModelVersion,
                    qualityScore = af.quality,
                    yaw = af.pose.yaw, pitch = af.pose.pitch, roll = af.pose.roll,
                    isLowQuality = !af.recognitionEligible
                )
            }
            // PersonMatcher persists matched faces itself. Persist rejected / failed-embedding
            // detections here so face counts and diagnostic overlays remain complete.
            faceDao.insertAll(faceEntities.filter { it.embeddingJson == null })
            val matchOutcomes = faceEntities.mapNotNull { face ->
                if (face.isLowQuality || face.embeddingJson == null) return@mapNotNull null
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
            lightweightBitmap?.recycle()
            faceBitmap?.recycle()
        }
    }

    /** Acquire a durable per-photo claim so the candidate bench and residual sweep cannot overlap. */
    private suspend fun claim(item: GalleryRepository.MediaItem, mode: String): Boolean {
        val uri = item.uri.toString()
        val now = android.os.SystemClock.uptimeMillis()
        if (mode == Mode.CANDIDATES) {
            return photoDao.claimForFaceProcessing(
                uri = uri,
                eligibleStatuses = listOf(PersonPhotoEntity.Status.CLIP_CANDIDATE),
                processingStatus = PersonPhotoEntity.Status.FACE_PROCESSING,
                claimedAt = now
            ) > 0
        }
        val inserted = photoDao.insertIfAbsent(
            PersonPhotoEntity(
                uri = uri,
                status = PersonPhotoEntity.Status.FACE_PROCESSING,
                capturedAt = item.dateMillis,
                lastAnalyzedAt = now
            )
        )
        if (inserted != -1L) return true
        return photoDao.claimForFaceProcessing(
            uri = uri,
            eligibleStatuses = listOf(
                PersonPhotoEntity.Status.UNPROCESSED,
                PersonPhotoEntity.Status.CLIP_CANDIDATE,
                PersonPhotoEntity.Status.HAS_FACES_UNMATCHED
            ),
            processingStatus = PersonPhotoEntity.Status.FACE_PROCESSING,
            claimedAt = now
        ) > 0
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
        val verdict = if (photosPerMin >= ThroughputTargetPhotosPerMinute) "met" else "below target"
        Log.i(
            Tag,
            "Throughput: $photosPerMin photos/min over ${elapsedMs / 1000}s (${stats.visited} photos) " +
                "— SLA ${ThroughputTargetPhotosPerMinute}/min $verdict. " +
                "CLIP gate: $reused stored vectors reused; no image vectors re-encoded."
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
        private const val CandidateWorkName = "gallery_face_candidates"
        const val WorkTag = "gallery_face_index_work"
        private const val ModeKey = "face_index_mode"
        private object Mode {
            const val CANDIDATES = "candidates"
            const val REMAINDER = "remainder"
        }
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
        private const val ProgressEvery = 5
        private const val MaxRetries = 3
        private const val CandidateBatchLimit = 24
        private const val ResidualPhotoIdleMillis = 900L
        private const val ResidualStartDelaySeconds = 20L
        private const val StaleClaimMillis = 20 * 60 * 1000L
        /** Phase 2 SLA target — photos/min while charging (spec: 30–60). Used by [logThroughput]. */
        private const val ThroughputTargetPhotosPerMinute = 30

        /** Spec Phase 2 worker default — battery-gated, no network requirement. */
        fun enqueue(context: Context, replaceExisting: Boolean = false) {
            val request = buildRequest(context, Mode.REMAINDER)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WorkName,
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        /** Starts promptly when CLIP finds person-like images, but only one photo is ever active. */
        fun enqueueCandidates(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CandidateWorkName,
                ExistingWorkPolicy.KEEP,
                buildRequest(context, Mode.CANDIDATES)
            )
        }

        /**
         * Schedule FaceIndexWorker to run after the current IndexWorker pass completes. Uses
         * APPEND_OR_REPLACE against IndexWorker's unique name, which appends behind any in-flight
         * work instead of forcing a sibling cancel — the failure mode we hit before this change.
         */
        fun enqueueRemainderAfterClip(context: Context) {
            val request = buildRequest(context, Mode.REMAINDER, ResidualStartDelaySeconds)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WorkName,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        private fun buildRequest(
            context: Context,
            mode: String,
            initialDelaySeconds: Long = 0L
        ) = OneTimeWorkRequestBuilder<FaceIndexWorker>()
            .addTag(WorkTag)
            .setInputData(workDataOf(ModeKey to mode))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                    .apply {
                        if (IndexPreferences.isChargingOnlyIndexing(context)) setRequiresCharging(true)
                    }
                    .build()
            )
            .apply {
                if (initialDelaySeconds > 0L) setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            }
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                DesignTokens.INDEX_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()

        /** Photo-row terminal statuses: any of these means the pipeline won't re-visit this URI. */
        private val PhotoRowTerminalStatuses = listOf(
            PersonPhotoEntity.Status.RESOLVED_TO_PERSON,
            PersonPhotoEntity.Status.GATED_NO_FACES,
            PersonPhotoEntity.Status.CLIP_UNAVAILABLE,
            PersonPhotoEntity.Status.NO_FACES,
            PersonPhotoEntity.Status.DECODE_FAILED,
            PersonPhotoEntity.Status.DUPLICATE_IN_BURST
        )

    }
}
