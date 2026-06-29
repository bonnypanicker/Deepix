package com.devomind.gallerysearch

import android.content.Context

object IndexPreferences {
    private const val PrefName = "index_prefs"
    private const val KeyAlbums = "selected_album_ids"
    private const val KeyLastIndexed = "last_indexed_time"
    private const val KeyOptimalThreads = "optimal_thread_count"
    private const val KeyShowPinnedCollections = "show_pinned_collections"
    private const val KeyGridColumnCount = "grid_column_count"
    private const val KeyCollageLayout = "use_collage_layout"
    private const val KeyIndexPaused = "index_paused"
    private const val KeyCleanupPaused = "cleanup_paused"

    fun saveSelectedAlbums(context: Context, albumIds: Set<String>) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KeyAlbums, albumIds)
            .apply()
    }

    fun loadSelectedAlbums(context: Context): Set<String> {
        val set = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getStringSet(KeyAlbums, emptySet())
            ?: emptySet()
        return set.toSet()
    }

    fun saveLastIndexedTime(context: Context) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putLong(KeyLastIndexed, System.currentTimeMillis())
            .apply()
    }

    fun getLastIndexedTime(context: Context): Long {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getLong(KeyLastIndexed, 0L)
    }

    fun loadLastIndexedTime(context: Context): Long = getLastIndexedTime(context)

    fun isIndexPaused(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyIndexPaused, false)
    }

    fun setIndexPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyIndexPaused, paused)
            .apply()
    }

    fun isCleanupPaused(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyCleanupPaused, false)
    }

    fun setCleanupPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyCleanupPaused, paused)
            .apply()
    }

    fun saveOptimalThreadCount(context: Context, count: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyOptimalThreads, count)
            .apply()
    }

    /** Returns 0 if no benchmark has been run yet. */
    fun getOptimalThreadCount(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyOptimalThreads, 0)
    }

    fun isShowPinnedInCollections(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyShowPinnedCollections, true)
    }

    fun setShowPinnedInCollections(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyShowPinnedCollections, enabled)
            .apply()
    }

    fun getGridColumnCount(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyGridColumnCount, DesignTokens.GRID_DEFAULT_COLUMNS)
    }

    fun setGridColumnCount(context: Context, count: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyGridColumnCount, count)
            .apply()
    }

    fun isCollageLayout(context: Context): Boolean {
        // Default to false to use the new grid mode by default
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyCollageLayout, false)
    }

    fun setCollageLayout(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyCollageLayout, enabled)
            .apply()
    }
}
