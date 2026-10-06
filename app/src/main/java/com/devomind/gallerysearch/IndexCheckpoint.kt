package com.devomind.gallerysearch

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The staged checkpoint would not move into place, but the staged copy itself is intact. */
class CheckpointRenameFailed(source: File, target: File) :
    IOException("Could not rename ${source.name} over ${target.name}")

/**
 * @param replaced true once the target holds the payload's bytes.
 * @param error why durability or the rename could not be confirmed, or null when both could. A non-null
 *  [error] beside `replaced = true` means the file is there and readable, just not yet forced to the
 *  flash — which is the older behaviour, not a regression.
 */
data class Checkpoint(val replaced: Boolean, val error: Throwable?)

/**
 * Checkpoint writing for the derived-data index files (embeddings, metadata).
 *
 * The payload is written to a staging sibling, forced out to the flash, then renamed over the target.
 * `rename(2)` replaces within one filesystem, so there is never an instant at which neither the old
 * file nor the new one exists — which is what the earlier `delete()`-then-`rename(2)` shape did, and a
 * kill inside that window cost a full re-index of the library.
 *
 * No logging here: this runs in plain JVM tests, where `android.util.Log` is not available, and the
 * caller is the one that knows which index and which journal are still standing.
 */
object IndexCheckpoint {

    /**
     * Writes [payload] to [staging] and renames it over [target].
     *
     * A payload that throws leaves the previous [target] untouched and discards the partial staging
     * file. A failed rename keeps the staging file, because it is the newest complete snapshot there
     * is and [promotable] can put it back in service. A failed `fsync` does not undo the checkpoint:
     * some filesystems refuse the call outright, and losing the data is worse than losing the guarantee.
     */
    fun write(target: File, staging: File, payload: (DataOutputStream) -> Unit): Checkpoint {
        var syncError: Throwable? = null
        try {
            FileOutputStream(staging).use { stream ->
                DataOutputStream(BufferedOutputStream(stream)).use(payload)
                // Without this the rename can land ahead of the bytes, and a power cut leaves a target
                // whose tail is zeros under a record count that promises more than it holds.
                syncError = try {
                    stream.fd.sync()
                    null
                } catch (error: Throwable) {
                    error
                }
            }
        } catch (error: Throwable) {
            // The payload never finished, so this staging file is worth nothing and must not be
            // mistaken for a checkpoint later.
            staging.delete()
            return Checkpoint(replaced = false, error = error)
        }

        // Some hosts report a refused rename by throwing rather than returning false. Either way the
        // staged file is whole, so it stays where it is and promotable() can bring it back.
        val renameError = try {
            if (staging.renameTo(target)) return Checkpoint(replaced = true, error = syncError)
            null
        } catch (error: Throwable) {
            error
        }
        return Checkpoint(replaced = false, error = renameError ?: CheckpointRenameFailed(staging, target))
    }

    /** True when the base is gone but a complete staged snapshot is waiting to become the base. */
    fun promotable(base: File, staging: File): Boolean = !base.exists() && staging.exists()
}
