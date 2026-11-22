package com.devomind.gallerysearch

import android.content.Context

/**
 * Which device folders (MediaStore bucket ids) an indexing pass does its work in.
 *
 * This is completely independent of the normal gallery view — the app always shows every photo.
 * It only controls where [IndexWorker] spends encoding work.
 *
 * Semantics:
 *  - empty set  => "all folders" (default) and future folders are included automatically.
 *  - non-empty  => encode the photos of those folders that are not indexed yet.
 *
 * An unticked folder is not "unsearchable": photos already embedded there keep their rows and stay
 * in AI search. Only a photo that leaves the library loses its row — see [IndexRetention].
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

    /**
     * The folders [next] will encode that [previous] did not — the only scope change that needs a
     * pass. Unchecking a folder is deliberately not in here: rows already indexed stay searchable
     * (see [IndexRetention]), so dropping a folder leaves nothing to rebuild.
     */
    fun addedFolders(previous: Set<String>, next: Set<String>, knownIds: Set<String>): Set<String> =
        covered(next, knownIds) - covered(previous, knownIds)

    /** The folders a stored scope covers; the empty set means every known folder. */
    private fun covered(scope: Set<String>, knownIds: Set<String>): Set<String> =
        if (scope.isEmpty()) knownIds else scope

    fun isAllFolders(context: Context): Boolean = getFolderIds(context).isEmpty()
}
