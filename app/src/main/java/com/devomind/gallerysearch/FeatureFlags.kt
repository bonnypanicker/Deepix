package com.devomind.gallerysearch

/**
 * Kill switches for features that cannot run without All-files access (MANAGE_EXTERNAL_STORAGE).
 * That permission was rejected by Play's All Files Access policy, so these entry points are hidden
 * in this release while the features are rebuilt on scoped storage (MediaStore
 * createTrashRequest / createWriteRequest / SAF).
 *
 * The feature code itself stays in place — flipping a flag back on is the whole migration, not a
 * re-implementation. Do not delete the flagged-off code.
 */
object FeatureFlags {

    /** Recycle Bin moves originals into app-private storage via direct file delete. Phase 2: system trash. */
    const val RECYCLE_BIN_ENABLED = false

    /** Safe vault writes an AES zip to a public folder via direct file I/O. Phase 2: SAF-backed storage. */
    const val SAFE_ENABLED = false

    /** Compression "Replace originals" overwrites the source file directly. Phase 2: createWriteRequest. */
    const val COMPRESSION_REPLACE_ENABLED = false

    /** Album folder copy/move writes photos into a folder album's own folder and deletes originals on
     *  move. Copy uses MediaStore insert (keep-both style) and stays usable; move deletes originals, so
     *  the whole entry point is hidden until the move half is rebuilt on scoped storage. */
    const val ALBUM_FOLDER_OPS_ENABLED = false
}
