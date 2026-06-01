package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri

class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun all(): Set<String> {
        return prefs.getStringSet(FavoritesKey, emptySet()).orEmpty()
    }

    fun isFavorite(uri: Uri): Boolean {
        return all().contains(uri.toString())
    }

    fun toggle(uri: Uri): Boolean {
        val key = uri.toString()
        val favorites = prefs.getStringSet(FavoritesKey, emptySet()).orEmpty().toMutableSet()
        val isFavorite = if (favorites.contains(key)) {
            favorites.remove(key)
            false
        } else {
            favorites.add(key)
            true
        }
        prefs.edit().putStringSet(FavoritesKey, favorites).apply()
        return isFavorite
    }

    companion object {
        private const val PrefsName = "gallery_favorites"
        private const val FavoritesKey = "favorite_uris"
    }
}
