package com.devomind.gallerysearch

import android.content.Context

/**
 * Owns which sort order each listing uses, and remembers it across restarts.
 *
 * Preferences are stored per scope (a screen's paging context key) with the global
 * default as the fallback, so changing sort inside one album doesn't reorder every
 * other screen. To move the app to a single shared order instead, make [optionFor]
 * return [globalDefault] unconditionally — nothing else has to change.
 *
 * Follows the [IndexPreferences] convention: same prefs file, context-passing
 * accessors, enum persisted by its stable string key.
 */
object SortManager {
    private const val PrefName = "index_prefs"
    private const val KeyGlobalSort = "sort_global"
    private const val ScopeKeyPrefix = "sort_scope_"

    /** The order [scopeKey] should use, falling back to the global default when unset. */
    fun optionFor(context: Context, scopeKey: String?): SortOption {
        val global = globalDefault(context)
        if (scopeKey == null) return global
        val stored = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(ScopeKeyPrefix + scopeKey, null)
            ?: return global
        return SortOption.fromKey(stored)
    }

    fun setOption(context: Context, scopeKey: String?, option: SortOption) {
        if (scopeKey == null) {
            setGlobalDefault(context, option)
            return
        }
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(ScopeKeyPrefix + scopeKey, option.key)
            .apply()
    }

    fun globalDefault(context: Context): SortOption {
        val stored = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(KeyGlobalSort, null)
        return SortOption.fromKey(stored)
    }

    fun setGlobalDefault(context: Context, option: SortOption) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyGlobalSort, option.key)
            .apply()
    }

    /** Convenience passthrough so callers need only one entry point. */
    fun sort(
        items: List<GalleryRepository.MediaItem>,
        option: SortOption
    ): List<GalleryRepository.MediaItem> = MediaSorter.sort(items, option)
}
