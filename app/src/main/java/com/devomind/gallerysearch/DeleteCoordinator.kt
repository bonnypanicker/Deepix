package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri

/**
 * Single decision point for "the user wants to delete these photos", shared by [MainActivity] and
 * [SmartCleanupActivity]. It resolves the destination from user settings:
 *
 *  - Recycle Bin on  → soft-delete into [BinManager] (30-day restore).
 *  - Recycle Bin off → permanent direct file delete via [MediaFileOps].
 *
 * Both direct paths require All-files access. Without it, this returns [Outcome.NeedsSystemDelete]
 * and the caller falls back to the platform `MediaStore.createDeleteRequest` consent flow (which
 * only removes files permanently — the bin needs direct access). Runs off the main thread.
 */
object DeleteCoordinator {

    sealed class Outcome {
        /**
         * Handled here. [toBin] distinguishes soft-delete from permanent for the confirmation copy;
         * when true, [binnedIds] identifies the created bin entries so the caller can offer UNDO.
         */
        data class Done(
            val succeeded: Int,
            val failed: Int,
            val toBin: Boolean,
            val binnedIds: List<String> = emptyList(),
            val succeededUris: List<Uri> = emptyList()
        ) : Outcome()

        /** No All-files access — caller should launch the system delete request. */
        data object NeedsSystemDelete : Outcome()
    }

    /** True when we can delete directly (bin move or permanent) without a system dialog. */
    fun canDeleteDirectly(context: Context): Boolean =
        StoragePermissions.hasAllFilesAccess(context)

    /** Whether deletes currently route through the Recycle Bin. */
    fun usesBin(context: Context): Boolean =
        IndexPreferences.isRecycleBinEnabled(context) && StoragePermissions.hasAllFilesAccess(context)

    /** Blocking; call from a background dispatcher. */
    fun delete(context: Context, uris: List<Uri>): Outcome {
        if (uris.isEmpty()) return Outcome.Done(0, 0, usesBin(context))
        if (!StoragePermissions.hasAllFilesAccess(context)) return Outcome.NeedsSystemDelete

        return if (IndexPreferences.isRecycleBinEnabled(context)) {
            val result = BinManager.moveToBin(context, uris)
            Outcome.Done(
                result.binned,
                result.failed,
                toBin = true,
                binnedIds = result.binnedIds,
                succeededUris = result.binnedUris
            )
        } else {
            val succeededUris = mutableListOf<Uri>()
            var failed = 0
            for (uri in uris) {
                if (MediaFileOps.deleteFileDirect(context, uri)) succeededUris += uri else failed++
            }
            Outcome.Done(
                succeededUris.size,
                failed,
                toBin = false,
                succeededUris = succeededUris
            )
        }
    }
}
