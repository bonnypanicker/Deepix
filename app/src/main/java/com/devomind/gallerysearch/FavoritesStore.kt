package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class FavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val dbRepository = DbRepository(context.applicationContext)

    init {
        runBlocking(Dispatchers.IO) {
            migrateLegacyIfNeeded()
        }
    }

    fun all(): Set<String> {
        return runBlocking(Dispatchers.IO) {
            dbRepository.getFavorites()
        }
    }

    fun isFavorite(uri: Uri): Boolean {
        return runBlocking(Dispatchers.IO) {
            dbRepository.isFavorite(uri.toString())
        }
    }

    fun toggle(uri: Uri): Boolean {
        return runBlocking(Dispatchers.IO) {
            dbRepository.toggleFavorite(uri.toString())
        }
    }

    private suspend fun migrateLegacyIfNeeded() {
        val legacy = prefs.getStringSet(FavoritesKey, emptySet()).orEmpty()
        if (legacy.isEmpty()) return

        val current = dbRepository.getFavorites()
        legacy.forEach { uri ->
            if (uri.isNotBlank() && uri !in current) {
                runCatching { dbRepository.insertFavorite(uri) }
            }
        }
        prefs.edit().remove(FavoritesKey).apply()
    }

    companion object {
        private const val PrefsName = "gallery_favorites"
        private const val FavoritesKey = "favorite_uris"
    }
}
