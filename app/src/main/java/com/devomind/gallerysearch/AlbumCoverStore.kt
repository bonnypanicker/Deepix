package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import org.json.JSONObject

class AlbumCoverStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun setCover(albumId: String, uri: Uri) {
        val covers = load()
        covers.put(albumId, uri.toString())
        save(covers)
    }

    fun getCoverUri(albumId: String): Uri? {
        val value = load().optString(albumId, "").takeIf { it.isNotBlank() } ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    fun cleanup(validAlbumIds: Set<String>) {
        val covers = load()
        val staleKeys = mutableListOf<String>()
        val keys = covers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in validAlbumIds) staleKeys += key
        }
        if (staleKeys.isEmpty()) return
        staleKeys.forEach(covers::remove)
        save(covers)
    }

    private fun load(): JSONObject {
        val raw = prefs.getString(KeyCovers, "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun save(covers: JSONObject) {
        prefs.edit().putString(KeyCovers, covers.toString()).apply()
    }

    companion object {
        private const val PrefsName = "album_covers"
        private const val KeyCovers = "album_cover_uris"
    }
}
