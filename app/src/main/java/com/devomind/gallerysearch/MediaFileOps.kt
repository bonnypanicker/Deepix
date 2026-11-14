package com.devomind.gallerysearch

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Direct-filesystem helpers used by the Recycle Bin and Safe while those features hold direct file
 * access.
 *
 * With direct file access the app can read a media item's real path from MediaStore and then operate
 * on the [File] directly — copy it into the bin, delete it, or write a restored copy back — with no
 * per-item system consent dialog. Both the bin and Safe are gated behind [FeatureFlags] in this
 * release (All-files access was removed for Play policy compliance), so these helpers only run when
 * the corresponding flag is on; everything here degrades to `false`/no-op if the file can't be
 * resolved.
 */
object MediaFileOps {

    private const val TAG = "MediaFileOps"

    /** Absolute filesystem path for a MediaStore image/video uri, or null if it can't be resolved. */
    fun resolvePath(context: Context, uri: Uri): String? {
        // Fast path: already a file uri.
        if (uri.scheme == "file") return uri.path

        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) {
                        val path = c.getString(idx)
                        if (!path.isNullOrBlank()) return path
                    }
                }
            }
        }.onFailure { Log.w(TAG, "resolvePath failed for $uri", it) }
        return null
    }

    /**
     * The folder that holds [uri], so a new sibling (e.g. an edited copy) can be written beside it
     * instead of into the app's own folder. On API 29+ this is a MediaStore `RELATIVE_PATH` like
     * `Pictures/Camera/` (with a trailing slash, as MediaStore requires); on older releases it is
     * an absolute directory path, since there is no `RELATIVE_PATH` column to write. Null when it
     * can't be determined, so callers fall back to their default folder.
     */
    fun resolveParentFolder(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver
                    .query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                            if (idx >= 0) {
                                val rel = c.getString(idx)
                                if (!rel.isNullOrBlank()) {
                                    return if (rel.endsWith("/")) rel else "$rel/"
                                }
                            }
                        }
                    }
            }.onFailure { Log.w(TAG, "resolveParentFolder failed for $uri", it) }
            return null
        }
        return resolvePath(context, uri)?.let { File(it).parent }
    }

    /**
     * The volume [uri] lives on, so an edited copy can be written back to the same one. A photo or
     * video kept on a card belongs on that card, not on internal storage at the same relative path.
     *
     * The name is what MediaStore's own `getContentUri` takes: `external_primary` for internal
     * storage, `1234-ABCD` for a card. Null below API 29, where volumes are addressed by DATA path
     * and there is no such column, and null when the row names only the aggregate `external`
     * volume — an insert through that alias lands on primary storage anyway.
     */
    fun resolveVolumeName(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.VOLUME_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                        if (idx >= 0) {
                            val volume = c.getString(idx)
                            if (!volume.isNullOrBlank() && volume != MediaStore.VOLUME_EXTERNAL) {
                                return volume
                            }
                        }
                    }
                }
        }.onFailure { Log.w(TAG, "resolveVolumeName failed for $uri", it) }
        return null
    }

    /** Deletes the underlying file directly and clears its MediaStore row. Returns true on success. */
    fun deleteFileDirect(context: Context, uri: Uri): Boolean {
        val path = resolvePath(context, uri) ?: return false
        val file = File(path)
        val removed = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
        if (removed) {
            // Drop the stale MediaStore row (delete() by uri is owner-restricted but usually works
            // for our own scans; ignore failures — the scan below reconciles).
            runCatching { context.contentResolver.delete(uri, null, null) }
            rescan(context, path)
        }
        return removed
    }

    /** Asks MediaStore to (re)index a path so galleries reflect an add/restore/delete. */
    fun rescan(context: Context, vararg paths: String) {
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, paths, null, null)
        }.onFailure { Log.w(TAG, "rescan failed", it) }
    }

    /**
     * Copies a source uri's bytes into [dest] (creating parents) and forces them out before
     * returning, because the caller's next step is deleting the original. A copy that only reached
     * the page cache is not a copy when the power goes, and by then there is nothing left to copy
     * from. A refused `sync` is logged, not fatal — same posture as [IndexCheckpoint], where losing
     * the guarantee is better than failing to bin a photo at all.
     */
    fun copyToFile(context: Context, source: Uri, dest: File): Boolean {
        return runCatching {
            dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                    // Both halves of the ordering matter: the buffered bytes have to reach the
                    // descriptor before it is forced, and the force has to happen before close().
                    output.flush()
                    runCatching { output.fd.sync() }
                        .onFailure { Log.w(TAG, "Sync of $dest refused; the copy stands unforced", it) }
                }
            } ?: return false
            true
        }.getOrDefault(false)
    }
}
