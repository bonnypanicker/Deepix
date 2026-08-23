package com.devomind.gallerysearch.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single detected face. Embedding is stored as JSON (Phase 2 swaps in the memory-mapped
 * flat-vector index); booleans follow the schema laid out in the Phase 1 spec.
 *
 * @param faceId auto-generated primary key.
 * @param photoUri MediaStore uri for the source photo.
 * @param bboxJson [x, y, width, height] in face-detection-bitmap pixels (the oriented decode
 *            capped at GalleryRepository.FaceDetectionMaxEdge with power-of-two inSampleSize),
 *            NOT full-resolution photo pixels. Callers rendering a crop must scale by the
 *            detection bitmap's dims — see PersonAlbumsActivity.resolvePhotoDimensions.
 * @param landmarksJson five (x, y) landmark pairs — YuNet semantic order.
 * @param embeddingJson face embedding (L2-normalized), null when not yet computed.
 *            Kept as JSON so Phase 2 can swap in a vector index without a DB migration.
 * @param embeddingModelVersion version tag of the model that produced [embeddingJson] — lets
 *            Phase 2/3 invalidate stale embeddings.
 * @param qualityScore composite 0..1 quality score.
 * @param yaw estimated yaw in degrees (0 = frontal).
 * @param pitch estimated pitch in degrees.
 * @param roll estimated roll in degrees.
 * @param isLowQuality whether this face falls below the face quality floor (still stored but
 *            excluded from exemplar selection).
 * @param isExemplar whether this face is currently the per-person exemplar.
 * @param personId reference to the owning person cluster, or null if unassigned (Phase 1).
 */
@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("photoUri"), Index("personId")]
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val faceId: Long = 0,
    val photoUri: String,
    val bboxJson: String,
    val landmarksJson: String,
    val embeddingJson: String? = null,
    // Retain the SQL default for schema compatibility; all current inserts use the Kotlin value.
    @ColumnInfo(defaultValue = "w600k_mbf_v2")
    val embeddingModelVersion: String = "w600k_mbf_v2",
    val qualityScore: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    /**
     * Clockwise quarter-turn (0/90/180/270) the photo was rotated by when this face cleared
     * the accept confidence (mid-confidence rotation-retry). bbox/landmarks remain in the
     * original upright frame; cover crops must apply this rotation to show the face upright.
     */
    @ColumnInfo(defaultValue = "0")
    val rotationDegrees: Int = 0,
    val isLowQuality: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isExemplar: Boolean = false,
    val personId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
