package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records what each stored CLIP embedding was encoded *from*, so an edited photo can be told apart
 * from an unvisited one.
 *
 * The embedding index is keyed by URI and URIs survive an in-place edit, which used to make an edited
 * photo look indexed forever: its embedding kept describing the old pixels. This table holds the file
 * properties the encoder saw, and a mismatch against them puts the photo back in the work queue.
 *
 * Kept separate from `media_metadata` because that table is refreshed with the library's *current*
 * state at the end of every pass — comparing an embedding against it would either always match (no
 * re-encode, the bug) or match only since the last successful pass (re-encoding photos that were
 * already re-encoded before a pause or a crash). A row here is written when the embedding is, so it
 * says exactly what it means.
 */
@Entity(tableName = "embedding_source")
data class EmbeddingSourceEntity(
    @PrimaryKey
    val uri: String,
    val dateModifiedMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val recordedAt: Long
) {
    /** Variables one row binds in a bulk `@Insert`: every column, since the primary key is the uri. */
    companion object {
        const val BindVariables = 6
    }
}
