package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri

/**
 * Single decision point for "the user wants to delete these photos", shared by [MainActivity],
 * [SmartCleanupActivity] and [ViewerActivity].
 *
 * All-files access (MANAGE_EXTERNAL_STORAGE) was removed for Play policy compliance, so the app can
 * no longer delete a file it doesn't own directly. Every delete therefore hands off to the platform's
 * MediaStore consent flow — [Outcome.NeedsSystemDelete] — which the caller turns into a
 * `MediaStore.createTrashRequest` (API 30+, recoverable system trash) or `createDeleteRequest`.
 *
 * The Recycle Bin (app-private storage) is gated behind [FeatureFlags.RECYCLE_BIN_ENABLED] and is off
 * in this release, so [usesBin] is always false. The bin code stays in place for the Phase 2 rebuild
 * on the system trash.
 */
object DeleteCoordinator {

    sealed class Outcome {
        /**
         * Handled here (kept for the bin rebuild; never produced while the bin is flagged off).
         * [toBin] distinguishes soft-delete from permanent for the confirmation copy.
         */
        data class Done(
            val succeeded: Int,
            val failed: Int,
            val toBin: Boolean,
            val binnedIds: List<String> = emptyList(),
            val succeededUris: List<Uri> = emptyList()
        ) : Outcome()

        /** No direct delete available — caller should launch the system MediaStore delete/trash request. */
        data object NeedsSystemDelete : Outcome()
    }

    /** The Recycle Bin is off in this release (no all-files access to move originals). */
    fun usesBin(context: Context): Boolean =
        FeatureFlags.RECYCLE_BIN_ENABLED && IndexPreferences.isRecycleBinEnabled(context)

    /** Blocking; call from a background dispatcher. Always routes to system consent. */
    fun delete(context: Context, uris: List<Uri>): Outcome {
        if (uris.isEmpty()) return Outcome.Done(0, 0, usesBin(context))
        // No all-files access: hand every delete to the platform MediaStore flow.
        return Outcome.NeedsSystemDelete
    }
}
