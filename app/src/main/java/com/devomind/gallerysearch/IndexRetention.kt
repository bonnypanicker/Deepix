package com.devomind.gallerysearch

/**
 * Which rows survive when an indexing pass reconciles the embedding index with the library.
 *
 * A pass used to reconcile against the *indexed folders* selection, so unchecking a folder deleted
 * every embedding already stored for it. The photos then disappeared from AI search while Albums,
 * Metadata, Tags, People and the gallery kept showing them — those read MediaStore/Room, not the
 * index — which reads as "CLIP is broken" even though every screen was telling the truth.
 *
 * The selection decides what a pass *encodes*; it no longer decides what stays searchable. A row
 * leaves the index only when its photo leaves the library (deleted, moved to the vault/bin, or on
 * storage that is gone), which is the change no stored row could ever answer for.
 */
object IndexRetention {

    /** Whether the embedding stored under [uri] still describes a photo the library holds. */
    fun keeps(uri: String, libraryUris: Set<String>): Boolean = uri in libraryUris
}
