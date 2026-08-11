package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per photo the face-index pipeline visits. Tracks the CLIP person-gate score (spec Phase 2)
 * and a perceptual hash used for burst-window duplicate detection.
 *
 * Status moves through: indexed → gated_no_faces → has_faces_for_match → assigned_to_person (or
 * new Person created). Each phase is idempotent so a revisited photo is cheap.
 *
 * @param dhash 64-bit perceptual difference hash for near-duplicate detection.
 * @param clipPersonScore Person-gate score: max cosine over the person-prompt pool, minus max over
 *           the non-person pool. Range roughly [-1, 1]. Everything below [GateThreshold] is "gated".
 * @param lastAnalyzedAt Elapsed realtime when the pipeline last inspected this photo.
 * @param status Pipeline status word — created, gated_no_faces, has_faces, resolved, archived, etc.
 * @param exemplarPriority Quality of the *current* photo as a burst sequence's exemplar candidate;
 *           updated whenever a higher-quality duplicate arrives (Phase 2 spec requirement).
 */
@Entity(
    tableName = "person_photos",
    indices = [Index("status"), Index("lastAnalyzedAt"), Index("capturedAt")]
)
data class PersonPhotoEntity(
    @PrimaryKey val uri: String,
    val dhash: Long = 0L,
    val clipPersonScore: Float = 0f,
    val lastAnalyzedAt: Long = 0L,
    val status: String = Status.UNPROCESSED,
    val faceCount: Int = 0,
    /** Highest-quality face seen in the linked burst sequence, used for exemplar replacement. */
    val exemplarQuality: Float = 0f,
    /** URI of the burst's current exemplar photo (i.e. the photo with the highest-quality face). */
    val exemplarPhotoUri: String? = null,
    /** Photo capture time (MediaStore dateMillis). Burst-window comparisons key off this, NOT
     * [createdAt] — during a backfill every row is *created* within seconds of its neighbours,
     * which would collapse the whole library into one giant "burst". */
    val capturedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    object Status {
        const val UNPROCESSED = "unprocessed"
        /** CLIP marked this as person-like; the low-priority face worker can pick it up immediately. */
        const val CLIP_CANDIDATE = "clip_candidate"
        /** Claimed by a face worker. Stale claims are released on the next worker start. */
        const val FACE_PROCESSING = "face_processing"
        const val GATED_NO_FACES = "gated_no_faces"
        /** No stored CLIP vector was available, so no second CLIP image inference is attempted. */
        const val CLIP_UNAVAILABLE = "clip_unavailable"
        const val HAS_FACES_UNMATCHED = "has_faces_unmatched"
        const val RESOLVED_TO_PERSON = "resolved_to_person"
        const val NO_FACES = "no_faces"
        /** Burst near-duplicate whose face content matches the incumbent — embedding skipped. */
        const val DUPLICATE_IN_BURST = "duplicate_in_burst"
        /** Bitmap decode failed — terminal so the pipeline doesn't retry it forever. */
        const val DECODE_FAILED = "decode_failed"
    }
}
