package com.devomind.gallerysearch

import android.content.Context

/**
 * Which device folders (MediaStore bucket ids) the AI search index covers.
 *
 * This is completely independent of the normal gallery view — the app always shows every photo.
 * It only controls what [IndexWorker] embeds for search.
 *
 * Semantics:
 *  - empty set  => "all folders" (default) and future folders are included automatically.
 *  - non-empty  => index exactly those folders; nothing else.
 */
object IndexScopeStore {
    private const val PrefName = "index_scope"
    private const val KeyFolderIds = "indexed_folder_ids"

    /** Included bucket ids; empty means "all folders". */
    fun getFolderIds(context: Context): Set<String> {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getStringSet(KeyFolderIds, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    /** Persists the included folders. Pass an empty set to mean "all folders". */
    fun setFolderIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KeyFolderIds, ids)
            .apply()
    }

    fun isAllFolders(context: Context): Boolean = getFolderIds(context).isEmpty()
}
