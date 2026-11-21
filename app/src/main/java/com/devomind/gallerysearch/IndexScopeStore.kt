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

    /**
     * The stored form of a folder picker's checks. Everything checked means the same thing as
     * "all folders" and is stored as the empty set: a list of every folder that happens to exist
     * today is a frozen snapshot, so folders created later would stop being covered. Only a real
     * subset is stored as a list.
     */
    fun normalizeSelection(selected: Set<String>, knownIds: Set<String>): Set<String> {
        if (knownIds.isEmpty()) return emptySet()
        if (selected.isEmpty() || selected.containsAll(knownIds)) return emptySet()
        return selected.intersect(knownIds)
    }

    fun isAllFolders(context: Context): Boolean = getFolderIds(context).isEmpty()
}
