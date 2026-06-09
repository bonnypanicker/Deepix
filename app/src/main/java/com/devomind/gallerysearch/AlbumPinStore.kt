package com.devomind.gallerysearch

import android.content.Context
import org.json.JSONArray

class AlbumPinStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun pin(albumId: String) {
        val ids = loadOrdered()
        if (albumId !in ids) {
            ids += albumId
            saveOrdered(ids)
        }
    }

    fun unpin(albumId: String) {
        val ids = loadOrdered()
        if (albumId in ids) {
            ids -= albumId
            saveOrdered(ids)
        }
    }

    fun isPinned(albumId: String): Boolean {
        return albumId in loadOrdered()
    }

    fun getPinnedAlbumIds(): List<String> {
        return loadOrdered()
    }

    fun setPinnedOrder(albumIds: List<String>) {
        saveOrdered(albumIds.toMutableList())
    }

    fun cleanup(validAlbumIds: Set<String>) {
        val ids = loadOrdered()
        val pruned = ids.filter { it in validAlbumIds }
        if (pruned.size != ids.size) {
            saveOrdered(pruned.toMutableList())
        }
    }

    private fun loadOrdered(): MutableList<String> {
        val raw = prefs.getString(KeyPinned, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            result += arr.optString(i, "")
        }
        return result.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveOrdered(ids: MutableList<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs.edit().putString(KeyPinned, arr.toString()).apply()
    }

    companion object {
        private const val PrefsName = "album_pins"
        private const val KeyPinned = "pinned_album_ids"
    }
}
