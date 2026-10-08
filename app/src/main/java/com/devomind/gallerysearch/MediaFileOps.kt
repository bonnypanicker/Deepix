package com.devomind.gallerysearch

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Direct-filesystem helpers used by the Recycle Bin and Safe once the app holds All-files access.
 *
 * With that permission the app can read a media item's real path from MediaStore and then operate on
 * the [File] directly — copy it into the bin, delete it, or write a restored copy back — with no
 * per-item system consent dialog. Callers must confirm [StoragePermissions.hasAllFilesAccess] first;
 * everything here degrades to `false`/no-op if the file can't be resolved.
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
