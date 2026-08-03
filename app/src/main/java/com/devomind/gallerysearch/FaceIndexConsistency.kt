package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import com.devomind.gallerysearch.db.GalleryDatabase
import org.json.JSONArray

/**
 * Phase 2 deliverable #12 — Room ↔ vector-index consistency.
 *
 * The durable source of truth for a face embedding through Phase 2 is the Room `faces.embeddingJson`
 * column; the [FaceVectorIndex] is a fast-read mmap overlay that is flushed periodically by the
 * worker. A crash between the Room commit and the next vector-index flush can leave a Face row
 * with no corresponding vector entry — harmless to matching (PersonMatcher falls back to JSON) but
 * it defeats the point of the overlay. This check repairs that divergence on cold start:
 *
 *   for every Face row that has an embeddingJson:
 *     if the vector index has no entry for its faceId → backfill it from embeddingJson (no decode,
 *       no re-encode — the embedding is right there in Room).
 *
 * Faces whose `embeddingJson` is null but whose `personId` is set are flagged for re-indexing by
 * resetting their owning photo row to UNPROCESSED so the next worker run re-embeds them. (The
 * spec's "re-embed from the stored crop/bbox" path applies when embeddingJson is also gone; that
 * needs the source bitmap and belongs to a future full-rebuild pass, not this cheap startup check.)
 *
 * Designed to run off the main thread (see [GallerySearchApp.onCreate]); at phone scale (thousands
 * of faces) a full scan is sub-second and cheaper than a sample-then-recheck dance.
 */
object FaceIndexConsistency {

    private const val Tag = "FaceIndexConsistency"

    /** Result of a consistency pass — surfaced to logs and (optionally) the worker. */
    data class Result(val checked: Int, val repaired: Int, val flaggedForReindex: Int)

    /**
     * Ensure every Face row with an embedding has a matching vector-index entry. Safe to call
     * concurrently with the worker: [FaceVectorIndex] is internally locked, and the Room reads
     * here are independent of the worker's per-photo writes.
     */
    suspend fun checkAndRepair(context: Context): Result {
        val app = context.applicationContext as GallerySearchApp
        val vectorIndex = app.faceVectorIndex
        val faceDao = GalleryDatabase.getInstance(app).faceDao()
        val photoDao = GalleryDatabase.getInstance(app).personPhotoDao()

        var checked = 0
        var repaired = 0
        var flagged = 0

        val faces = runCatching { faceDao.findAllWithEmbeddings() }.getOrDefault(emptyList())
        for (face in faces) {
            checked++
            if (vectorIndex.contains(face.faceId)) continue
            val emb = face.embeddingJson?.let { decodeEmbedding(it) }
            if (emb != null) {
                vectorIndex.put(face.faceId, emb)
                repaired++
            } else if (face.personId != null) {
                // Assigned face with no embedding at all — its photo needs re-processing.
                runCatching {
                    photoDao.setStatus(face.photoUri, com.devomind.gallerysearch.db.PersonPhotoEntity.Status.UNPROCESSED)
                }
                flagged++
            }
        }
        if (repaired > 0) vectorIndex.flush()

        Log.i(
            Tag,
            "Consistency check: $checked faces, $repaired vectors backfilled, $flagged flagged for re-index."
        )
        return Result(checked = checked, repaired = repaired, flaggedForReindex = flagged)
    }

    private fun decodeEmbedding(json: String): FloatArray? = runCatching {
        val arr = JSONArray(json)
        FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
    }.getOrNull()
}
