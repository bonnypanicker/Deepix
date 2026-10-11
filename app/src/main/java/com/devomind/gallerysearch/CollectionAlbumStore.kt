package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * What an album is made of. [Device] albums are the system's own folders, read from MediaStore;
 * the other three are collections this app saves and draws into the Albums page.
 */
enum class AlbumKind {
    Device,
    Smart,
    Folder,
    Tags
}

/**
 * An album the user created rather than a folder the device already had.
 *
 * The kinds share a record because they share everything the Albums page asks of an album — a name,
 * a cover, a count, pinning, rename, delete — and differ only in how membership is decided: a
 * [AlbumKind.Smart] album stores the URIs a search returned, while a folder or tag album is resolved
 * from the library on every render, so it cannot go stale.
 */
data class CollectionAlbum(
    val id: String,
    val name: String,
    val prompt: String,
    val searchMode: String,
    val memberUris: List<String>,
    val coverUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val kind: AlbumKind = AlbumKind.Smart
) {
    fun toAlbum(): GalleryRepository.Album = GalleryRepository.Album(
        id = id,
        name = name,
        count = memberUris.size,
        coverUri = coverUri?.let(Uri::parse),
        kind = kind
    )
}

class CollectionAlbumStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun getAll(): List<CollectionAlbum> {
        val raw = prefs.getString(KeyAlbums, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val result = mutableListOf<CollectionAlbum>()
        for (i in 0 until arr.length()) {
            result += parseAlbum(arr.optJSONObject(i))
        }
        return result
    }

    fun get(id: String): CollectionAlbum? {
        return getAll().find { it.id == id }
    }

    fun upsert(album: CollectionAlbum) {
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

    fun isCollectionId(id: String): Boolean = id.startsWith(SMART_PREFIX)

    private fun parseAlbum(json: JSONObject?): CollectionAlbum {
        if (json == null) return CollectionAlbum("", "", "", "", emptyList(), null, 0L, 0L)
        val memberArr = json.optJSONArray("memberUris")
        val members = mutableListOf<String>()
        if (memberArr != null) {
            for (i in 0 until memberArr.length()) {
                members += memberArr.optString(i, "")
            }
        }
        return CollectionAlbum(
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

    private fun albumToJson(album: CollectionAlbum): JSONObject {
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

    private fun saveAll(albums: List<CollectionAlbum>) {
        val arr = JSONArray()
        albums.forEach { arr.put(albumToJson(it)) }
        prefs.edit().putString(KeyAlbums, arr.toString()).apply()
    }

    companion object {
        const val SMART_PREFIX = "smart:"
        const val MAX_SMART_MEMBERS = 800

        /** Unchanged names on purpose: these are the on-disk format of every existing install. */
        private const val PrefsName = "smart_albums"
        private const val KeyAlbums = "smart_album_data"
    }
}
