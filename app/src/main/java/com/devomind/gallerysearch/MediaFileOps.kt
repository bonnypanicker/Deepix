package com.devomind.gallerysearch

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File

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
        // Delete the MediaStore row FIRST, while the file still exists. Removing the file with
        // File.delete() first leaves a row the resolver then refuses to drop, and a ghost row
        // keeps the deleted photo in the timeline until MediaProvider's idle maintenance prunes
        // it minutes later — pull-to-refresh kept re-querying it back into the grid. A successful
        // row delete removes the underlying file too, so the File.delete below is only cleanup
        // for when the row delete was refused.
        val rowDeleted = runCatching { context.contentResolver.delete(uri, null, null) }.isSuccess
        val fileExisted = file.exists()
        val fileRemoved = runCatching { !fileExisted || file.delete() }.getOrDefault(false)
        if (!rowDeleted && fileRemoved) {
            // Row delete refused and we removed the file ourselves: MediaStore now holds a ghost
            // row. Scanning the file's own path does nothing (it no longer exists); scanning the
            // parent directory reconciles rows against what is actually on disk.
            file.parent?.let { rescan(context, it) }
        } else if (rowDeleted && fileExisted && !fileRemoved) {
            Log.w(TAG, "MediaStore row deleted but the file survived at $path")
        }
        return fileRemoved || rowDeleted
    }

    /** Asks MediaStore to (re)index a path so galleries reflect an add/restore/delete. */
    fun rescan(context: Context, vararg paths: String) {
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, paths, null, null)
        }.onFailure { Log.w(TAG, "rescan failed", it) }
    }

    /** Copies a source uri's bytes into [dest] (creating parents). Returns true on success. */
    fun copyToFile(context: Context, source: Uri, dest: File): Boolean {
        return runCatching {
            dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return false
            true
        }.getOrDefault(false)
    }
}
