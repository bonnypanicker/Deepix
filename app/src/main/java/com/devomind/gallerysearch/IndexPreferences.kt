package com.devomind.gallerysearch

import android.content.Context

object IndexPreferences {
    private const val PrefName = "index_prefs"
    private const val KeyAlbums = "selected_album_ids"
    private const val KeyLastIndexed = "last_indexed_time"
    private const val KeyOptimalThreads = "optimal_thread_count"
    private const val KeyChosenEp = "chosen_ep"

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

    fun saveChosenEp(context: Context, ep: ExecutionProviderSelector.Ep) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyChosenEp, ep.name)
            .apply()
    }

    fun getChosenEp(context: Context): ExecutionProviderSelector.Ep? {
        val name = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(KeyChosenEp, null)
        return name?.let { runCatching { ExecutionProviderSelector.Ep.valueOf(it) }.getOrNull() }
    }
}
