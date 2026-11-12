package com.devomind.gallerysearch

/**
 * Kill switches for the features that used to need All-files access (MANAGE_EXTERNAL_STORAGE). That
 * permission was rejected by Play's All Files Access policy and stays removed, so each feature has
 * been rebuilt on scoped storage (MediaStore createTrashRequest / createWriteRequest / SAF) and its
 * flag is now on for this release.
 *
 * The flags stay so a feature can be turned off without ripping its code out — turning one off is a
 * hidden entry point, not a removal. Do not delete the code behind a flag.
 */
object FeatureFlags {

    /** Recycle Bin. Rebuilt on scoped storage: on API 30+ it *is* the platform's system trash
     *  (MediaStore createTrashRequest, no direct file access), and below API 30 it keeps using the
     *  app-private bin. See [BinStore]. */
    const val RECYCLE_BIN_ENABLED = true

    /** Safe vault. Rebuilt on scoped storage: the AES zip is a file in `Documents/PixaSafe/` by
     *  default — written through MediaStore, so no permission — or the app storage, a folder, or a
     *  single file the user picked through the system picker (persisted). See [VaultStorage]. No
     *  public vault folder by path, so no All-files access. */
    const val SAFE_ENABLED = true

    /** Compression "Replace originals". Rebuilt on scoped storage: the original's own MediaStore row
     *  is overwritten in place through `openOutputStream` under the user's `createWriteRequest`
     *  consent, so the photo keeps its URI and the mode needs no direct file access. Below API 30 the
     *  legacy write permission still covers a direct overwrite. See [CompressionEngine]. */
    const val COMPRESSION_REPLACE_ENABLED = true

    /** Album folder copy/move writes photos into a folder album's own folder and deletes originals on
     *  move. Both halves now run on scoped storage: the copy goes through a MediaStore insert
     *  ([AlbumFolderOps]), and the originals a move removes go through the delete policy
     *  ([DeleteCoordinator] → createTrashRequest), so nothing touches a shared-storage File handle. */
    const val ALBUM_FOLDER_OPS_ENABLED = true
}
