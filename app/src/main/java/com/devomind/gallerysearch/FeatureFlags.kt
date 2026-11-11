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

    /** Recycle Bin. Rebuilt on scoped storage: on API 30+ it *is* the platform's system trash
     *  (MediaStore createTrashRequest, no direct file access), and below API 30 it keeps using the
     *  app-private bin. See [BinStore]. */
    const val RECYCLE_BIN_ENABLED = true

    /** Safe vault writes an AES zip to a public folder via direct file I/O. Phase 2: SAF-backed storage. */
    const val SAFE_ENABLED = false

    /** Compression "Replace originals" overwrites the source file directly. Phase 2: createWriteRequest. */
    const val COMPRESSION_REPLACE_ENABLED = false

    /** Album folder copy/move writes photos into a folder album's own folder and deletes originals on
     *  move. Both halves now run on scoped storage: the copy goes through a MediaStore insert
     *  ([AlbumFolderOps]), and the originals a move removes go through the delete policy
     *  ([DeleteCoordinator] → createTrashRequest), so nothing touches a shared-storage File handle. */
    const val ALBUM_FOLDER_OPS_ENABLED = true
}
