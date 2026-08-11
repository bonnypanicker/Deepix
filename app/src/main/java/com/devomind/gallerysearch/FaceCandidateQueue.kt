package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonPhotoDao
import com.devomind.gallerysearch.db.PersonPhotoEntity

/**
 * Bridges the CLIP indexer and the deliberately small face side-bench.
 *
 * It consumes vectors the indexer has already produced, scores them against the person prompt
 * pool, and persists only likely-person / grey-zone photos. The face worker therefore never has
 * to decode and run MobileCLIP a second time just to decide whether YuNet should run.
 */
class FaceCandidateQueue(context: Context) {
    private val appContext = context.applicationContext
    private val photoDao = GalleryDatabase.getInstance(appContext).personPhotoDao()

    /**
     * Add a freshly indexed CLIP batch to the face side-bench. Terminal rows are left untouched:
     * a new CLIP pass must never resurrect a photo that has already completed face processing.
     */
    suspend fun enqueueCandidates(
        indexed: List<GalleryRepository.IndexedEmbedding>,
        mediaByUri: Map<String, GalleryRepository.MediaItem>
    ): Int {
        if (indexed.isEmpty()) return 0
        val textEncoder = (appContext as GallerySearchApp).sharedEncoders.getTextEncoder()
        var queued = 0
        for (entry in indexed) {
            val item = mediaByUri[entry.uri.toString()] ?: continue
            val verdict = ClipPersonGate.scoreEmbedding(textEncoder, entry.vector, entry.uri.toString())
            // Keep the grey band: it protects recall and continues to collect calibration data.
            if (!verdict.hasPerson && !verdict.isGreyZone) continue

            val existing = photoDao.findByUri(entry.uri.toString())
            if (existing?.status in PersonPhotoDao.TerminalStatuses) continue
            photoDao.insert(
                (existing ?: PersonPhotoEntity(uri = entry.uri.toString(), capturedAt = item.dateMillis)).copy(
                    clipPersonScore = verdict.gateScore,
                    status = PersonPhotoEntity.Status.CLIP_CANDIDATE,
                    capturedAt = item.dateMillis,
                    // A queue time is not an analysis time; zero keeps candidate ordering stable.
                    lastAnalyzedAt = 0L
                )
            )
            queued++
        }
        if (queued > 0) Log.d(Tag, "Queued $queued CLIP-qualified photos for the face side-bench.")
        return queued
    }

    private companion object {
        const val Tag = "FaceCandidateQueue"
    }
}
