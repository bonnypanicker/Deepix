package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class SmartAlbum(
    val id: String,
    val name: String,
    val prompt: String,
    val searchMode: String,
    val memberUris: List<String>,
    val coverUri: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toAlbum(): GalleryRepository.Album = GalleryRepository.Album(
        id = id,
        name = name,
        count = memberUris.size,
        coverUri = coverUri?.let(Uri::parse),
        isSmart = true
    )
}

class SmartAlbumStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun getAll(): List<SmartAlbum> {
        val raw = prefs.getString(KeySmartAlbums, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val result = mutableListOf<SmartAlbum>()
        for (i in 0 until arr.length()) {
            result += parseSmartAlbum(arr.optJSONObject(i))
        }
        return result
    }

    fun get(id: String): SmartAlbum? {
        return getAll().find { it.id == id }
    }

    fun upsert(album: SmartAlbum) {
        val albums = getAll().toMutableList()
        val idx = albums.indexOfFirst { it.id == album.id }
        if (idx >= 0) {
            albums[idx] = album
        } else {
            albums += album
        }
        saveAll(albums)
    }

    fun delete(id: String) {
        val albums = getAll().toMutableList()
        albums.removeAll { it.id == id }
        saveAll(albums)
    }

    fun isSmartId(id: String): Boolean = id.startsWith(SMART_PREFIX)

    private fun parseSmartAlbum(json: JSONObject?): SmartAlbum {
        if (json == null) return SmartAlbum("", "", "", "", emptyList(), null, 0L, 0L)
        val memberArr = json.optJSONArray("memberUris")
        val members = mutableListOf<String>()
        if (memberArr != null) {
            for (i in 0 until memberArr.length()) {
                members += memberArr.optString(i, "")
            }
        }
        return SmartAlbum(
            id = json.optString("id", ""),
            name = json.optString("name", ""),
            prompt = json.optString("prompt", ""),
            searchMode = json.optString("searchMode", "Hybrid"),
            memberUris = members.filter { it.isNotEmpty() },
            coverUri = json.optString("coverUri", "").takeIf { it.isNotEmpty() },
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L)
        )
    }

    private fun albumToJson(album: SmartAlbum): JSONObject {
        val memberArr = JSONArray()
        album.memberUris.forEach { memberArr.put(it) }
        return JSONObject().apply {
            put("id", album.id)
            put("name", album.name)
            put("prompt", album.prompt)
            put("searchMode", album.searchMode)
            put("memberUris", memberArr)
            put("coverUri", album.coverUri ?: "")
            put("createdAt", album.createdAt)
            put("updatedAt", album.updatedAt)
        }
    }

    private fun saveAll(albums: List<SmartAlbum>) {
        val arr = JSONArray()
        albums.forEach { arr.put(albumToJson(it)) }
        prefs.edit().putString(KeySmartAlbums, arr.toString()).apply()
    }

    companion object {
        const val SMART_PREFIX = "smart:"
        const val MAX_SMART_MEMBERS = 800

        private const val PrefsName = "smart_albums"
        private const val KeySmartAlbums = "smart_album_data"
    }
}
