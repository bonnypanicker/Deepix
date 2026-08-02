package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Quietly verifies likely people photos with YuNet after CLIP has started indexing. This is a
 * separate unique worker, deliberately has no foreground service, notification, or UI progress.
 */
class FaceScanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.Default) {
            val app = applicationContext as GallerySearchApp
            val textEncoder = app.sharedEncoders.getTextEncoder(ThreadCount)
            val repository = GalleryRepository(applicationContext, textEncoder = textEncoder)
            val scope = IndexScopeStore.getFolderIds(applicationContext)
            val images = repository.getImageItemsForAlbumIds(scope)
            val validUris = images.mapTo(HashSet()) { it.uri.toString() }
            val store = FaceResultStore(applicationContext)
            val previouslyScanned = store.load(validUris).scannedUris
            val candidateUris = likelyPeopleUris(repository)
            val candidates = images.filter { item ->
                item.uri.toString() in candidateUris && item.uri.toString() !in previouslyScanned
            }
            if (candidates.isEmpty()) return@withContext Result.success()

            Log.i(Tag, "Scanning ${candidates.size} CLIP-selected face candidates")
            val pendingResults = LinkedHashMap<String, Int>()
            // The scan worker preserves its original tuning (higher confidence, lower min-face-size)
            // since it was tuned for the CLIP prefilter; Phase 1 defaults apply elsewhere.
            YuNetDetector.forScanWorker(applicationContext).use { detector ->
                candidates.forEachIndexed { index, item ->
                    ensureActive()
                    val count = runCatching {
                        repository.loadBitmapForFaceDetection(item.uri)?.useBitmap(detector::detectFaceCount) ?: 0
                    }.onFailure { Log.w(Tag, "Face scan failed for ${item.uri}", it) }.getOrDefault(0)
                    pendingResults[item.uri.toString()] = count
                    if ((index + 1) % SaveEvery == 0) {
                        store.merge(pendingResults, validUris)
                        pendingResults.clear()
                    }
                }
            }
            if (pendingResults.isNotEmpty()) store.merge(pendingResults, validUris)
            Result.success()
        }
    } catch (error: Throwable) {
        Log.w(Tag, "Face candidate scan failed on attempt $runAttemptCount", error)
        if (runAttemptCount < MaxRetries) Result.retry() else Result.success()
    }

    private fun likelyPeopleUris(repository: GalleryRepository): Set<String> {
        // Candidate-first by design: these phrases cover portraits, groups, candid people shots,
        // and common selfie wording without turning the first pass into a library-wide scan.
        return CandidateQueries.asSequence()
            .flatMap { query -> repository.search(query).asSequence().take(CandidatesPerQuery) }
            .map { it.uri.toString() }
            .toCollection(LinkedHashSet())
    }

    private inline fun <T> android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> T): T =
        try { block(this) } finally { recycle() }

    companion object {
        const val WorkName = "gallery_face_candidate_scan"
        private const val Tag = "FaceScanWorker"
        private const val ThreadCount = 2
        private const val CandidatesPerQuery = 350
        private const val SaveEvery = 12
        private const val MaxRetries = 2
        private val CandidateQueries = listOf(
            "face", "person", "people", "man", "woman", "group photo", "portrait", "selfie"
        )

        fun enqueue(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<FaceScanWorker>()
                // Let CLIP save its first embeddings and keep the face pass subordinate on cold start.
                .setInitialDelay(45, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, DesignTokens.INDEX_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WorkName, ExistingWorkPolicy.KEEP, request)
        }
    }
}
