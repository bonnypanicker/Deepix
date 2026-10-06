package com.devomind.gallerysearch

/**
 * The file properties a stored embedding was encoded from. Two of them change on an in-place edit
 * (modified time, size) and two describe pixels (width, height), which catches an edit that touches up
 * the frame while leaving the clock alone.
 */
data class MediaSignature(
    val dateModifiedMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)

/**
 * Whether a photo still needs the encoder, given that the embedding index is keyed by URI and a URI
 * survives an in-place edit.
 *
 * Before this, an edited photo looked indexed forever: the map had its URI, so its embedding went on
 * describing pixels that no longer existed. The decision is split out (and off `MediaItem`, which
 * needs a live `Uri`) so it can be tested on the host.
 */
object EmbeddingFreshness {

    /**
     * A signature no file can have. Written in place of deleting a row when a photo is edited: a
     * *missing* row means "embedded before signatures existed, trust the embedding", while this means
     * "the embedding is known to describe an older version of this file". Both fit one comparison —
     * recorded != current — and it survives the embedding still being on disk.
     */
    val StaleSignature = MediaSignature(-1L, -1L, -1, -1)

    enum class Decision {
        /** No embedding yet, or the embedding predates the file. */
        Encode,

        /** The embedding was made from this exact file state. */
        Current,

        /**
         * Embedded but never signed — every photo in the library on the first pass after this shipped.
         * Signed from the current item rather than re-encoded, so updating the app doesn't put the whole
         * collection back through the encoder.
         */
        Backfill
    }

    fun decide(embedded: Boolean, recorded: MediaSignature?, current: MediaSignature): Decision = when {
        !embedded -> Decision.Encode
        recorded == null -> Decision.Backfill
        recorded != current -> Decision.Encode
        else -> Decision.Current
    }
}
