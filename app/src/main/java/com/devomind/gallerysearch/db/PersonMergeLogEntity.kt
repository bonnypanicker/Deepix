package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only audit trail for people-cluster mutations. Lifecycle invariants:
 * - Rows are never mutated or deleted after insert.
 * - "Undo" is a row (an [Event.UNDO_MERGE] / [Event.UNDO_SPLIT]) referencing the original event via
 *   [refEventId]; never deletes the earlier row.
 * - "Read current state" means replaying the full event stream.
 *
 * Both auto-suggest actions (from maintenance) and user-confirmed actions write rows here.
 * Auto-suggest rows carry [AutoSuggestionKind] in [metricJson]; user confirmations echo them as-is
 * (or add [EventOrigin] = [Origin.USER]).
 */
@Entity(
    tableName = "person_merge_log",
    indices = [Index("eventKind"), Index("refEventId"), Index("personId")]
)
data class PersonMergeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** One of the [Event] constants. */
    val eventKind: Int,

    /** The subject of the event: for MERGE this is the surviving person's id; for SPLIT it's the
     * fragmented person's id; for UNDO_* it's the person restored. */
    val personId: Long,

    /** Optional partner person id (used by MERGE to record the absorbed person). */
    val otherPersonId: Long = 0,

    /** Freeform metadata: centroid values, exemplar ids involved, etc. Kept for re-clustering
     * simply without recomputing. */
    val metricJson: String? = null,

    /** For UNDO_* events, the [id] of the original event being reversed. */
    val refEventId: Long = 0,

    /** Whether this entry came from an automatic maintenance pass or from a user. */
    val origin: String = Origin.SYSTEM,

    val createdAt: Long = System.currentTimeMillis(),
) {
    object Event {
        const val NONE = 0
        const val SUGGEST_MERGE = 1     // system: A and B look similar; user should consider merging
        const val SUGGEST_SPLIT = 2     // system: this person's cluster looks bimodal
        const val MERGE = 3             // A absorbs B into personId
        const val SPLIT = 4             // personId's faces redistributed into new Persons
        const val UNDO_MERGE = 5        // reverses [Event.MERGE]
        const val UNDO_SPLIT = 6        // reverses [Event.SPLIT]
    }

    object Origin {
        const val SYSTEM = "system"
        const val USER = "user"
    }
}
