package com.devomind.gallerysearch

import android.content.Context

object IndexPreferences {
    private const val PrefName = "index_prefs"
    private const val KeyAlbums = "selected_album_ids"

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
}
