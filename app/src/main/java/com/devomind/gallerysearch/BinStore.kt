package com.devomind.gallerysearch

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/**
 * One row in the Recycle Bin, whichever storage backs it. [displayUri] is always a content uri, so
 * the grid can load a thumbnail without knowing where the bytes live; [appEntry] is non-null only
 * for the app-private bin used below API 30.
 */
data class BinItem(
    val key: String,
    val displayUri: Uri,
    val fileName: String,
    val mimeType: String?,
    val dateTrashed: Long = 0L,
    val appEntry: BinEntry? = null
)

/**
 * The Recycle Bin's contents.
 *
 * On API 30+ the bin *is* the platform's system trash: deletes go to MediaStore's recoverable trash
 * (see [DeleteCoordinator]), so this screen is a query over trashed items and Restore /
 * Delete-forever are MediaStore consent requests. That is what lets the feature keep working without
 * All-files access.
 *
 * Below API 30 there is no system trash, so the app-private bin ([BinManager], `filesDir/bin`) is
 * used and its operations complete synchronously.
 *
 * [restore], [deleteForever] and [empty] therefore return an [IntentSender] the caller must launch
 * when the platform has to ask for consent, or null when the work was already done in place.
 */
@Suppress("NewApi")
object BinStore {

    fun usesSystemTrash(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun count(context: Context): Int =
        if (usesSystemTrash()) trashedItems(context).size else BinManager.count(context)

    fun list(context: Context): List<BinItem> =
        if (usesSystemTrash()) trashedItems(context) else BinManager.listAsItems(context)

    /** Un-trashes [items]; returns the consent request to launch, or null when done in place. */
    fun restore(context: Context, items: List<BinItem>): IntentSender? =
        if (usesSystemTrash()) {
            MediaStore.createTrashRequest(
                context.contentResolver,
                items.map { it.displayUri },
                false
            ).intentSender
        } else {
            items.forEach { item -> item.appEntry?.let { BinManager.restore(context, it) } }
            null
        }

    /** Permanently deletes [items]. Same contract as [restore]. */
    fun deleteForever(context: Context, items: List<BinItem>): IntentSender? =
        if (usesSystemTrash()) {
            MediaStore.createDeleteRequest(
                context.contentResolver,
                items.map { it.displayUri }
            ).intentSender
        } else {
            items.forEach { item -> item.appEntry?.let { BinManager.deleteForever(context, it) } }
            null
        }

    /** Empties the bin. Same contract as [restore]. */
    fun empty(context: Context): IntentSender? =
        if (usesSystemTrash()) {
            val uris = trashedItems(context).map { it.displayUri }
            if (uris.isEmpty()) null
            else MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        } else {
            BinManager.emptyBin(context)
            null
        }

    // ---- System trash (API 30+) ----

    /** Trashed photos and videos this app put in the system trash, newest first. */
    private fun trashedItems(context: Context): List<BinItem> = buildList {
        addAll(queryTrashed(context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)))
        addAll(queryTrashed(context, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)))
    }.sortedByDescending { it.dateTrashed }

    private fun queryTrashed(context: Context, collection: Uri): List<BinItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        // The trashed flag is not a normal selection column — the platform exposes it as a query
        // argument, so MATCH_ONLY is what narrows a media collection down to its trashed rows.
        val queryArgs = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )
        }
        val items = mutableListOf<BinItem>()
        val cursor = context.contentResolver.query(collection, projection, queryArgs, null) ?: return items
        cursor.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idColumn))
                items += BinItem(
                    key = uri.toString(),
                    displayUri = uri,
                    fileName = c.getString(nameColumn) ?: "item",
                    mimeType = c.getString(mimeColumn),
                    dateTrashed = c.getLong(dateColumn)
                )
            }
        }
        return items
    }
}
