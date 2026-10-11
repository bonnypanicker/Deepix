package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
 * [AlbumKind.Smart] album keeps the URIs a search returned, while a [AlbumKind.Folder] or
 * [AlbumKind.Tags] album holds a rule and is resolved from the library on every render, so it cannot
 * go stale. [prompt], [searchMode] and [memberUris] are inert for the live kinds, [folderPath] and
 * [tagNames] for smart ones; the alternative was three stores, three serializations and three copies
 * of the same Albums-page splice.
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
    val kind: AlbumKind = AlbumKind.Smart,
    /** Folder albums: the device folder this album mirrors, without its storage prefix. */
    val folderPath: String = "",
    /** Tag albums: the tags to collect — a photo carrying any one of them belongs. */
    val tagNames: List<String> = emptyList()
) {
    /**
     * The cell on the Albums grid. A live album passes its count and cover from the library as it
     * stands: the URIs a folder held yesterday are not the folder, and only a smart album has a
     * stored list worth reporting.
     */
    fun toAlbum(count: Int = memberUris.size, coverUri: String? = this.coverUri): GalleryRepository.Album =
        GalleryRepository.Album(
            id = id,
            name = name,
            count = count,
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

    private fun parseAlbum(json: JSONObject?): CollectionAlbum {
        if (json == null) return CollectionAlbum("", "", "", "", emptyList(), null, 0L, 0L)
        val memberArr = json.optJSONArray("memberUris")
        val members = mutableListOf<String>()
        if (memberArr != null) {
            for (i in 0 until memberArr.length()) {
                members += memberArr.optString(i, "")
            }
        }
        val id = json.optString("id", "")
        val tagArr = json.optJSONArray("tagNames")
        val tags = mutableListOf<String>()
        if (tagArr != null) {
            for (i in 0 until tagArr.length()) {
                tags += tagArr.optString(i, "")
            }
        }
        return CollectionAlbum(
            id = id,
            name = json.optString("name", ""),
            prompt = json.optString("prompt", ""),
            searchMode = json.optString("searchMode", "Hybrid"),
            memberUris = members.filter { it.isNotEmpty() },
            coverUri = json.optString("coverUri", "").takeIf { it.isNotEmpty() },
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L),
            // Every album written before kind existed was a smart one, and an id left standing from
            // such a record says so too — read the prefix rather than defaulting everything to Smart.
            kind = json.optString("kind", "").toAlbumKind() ?: kindOfId(id),
            folderPath = json.optString("folderPath", ""),
            tagNames = tags.filter { it.isNotEmpty() }
        )
    }

    private fun albumToJson(album: CollectionAlbum): JSONObject {
        val memberArr = JSONArray()
        album.memberUris.forEach { memberArr.put(it) }
        val tagArr = JSONArray()
        album.tagNames.forEach { tagArr.put(it) }
        return JSONObject().apply {
            put("id", album.id)
            put("name", album.name)
            put("prompt", album.prompt)
            put("searchMode", album.searchMode)
            put("memberUris", memberArr)
            put("coverUri", album.coverUri ?: "")
            put("createdAt", album.createdAt)
            put("updatedAt", album.updatedAt)
            put("kind", album.kind.name)
            put("folderPath", album.folderPath)
            put("tagNames", tagArr)
        }
    }

    private fun saveAll(albums: List<CollectionAlbum>) {
        val arr = JSONArray()
        albums.forEach { arr.put(albumToJson(it)) }
        prefs.edit().putString(KeyAlbums, arr.toString()).apply()
    }

    private fun String.toAlbumKind(): AlbumKind? =
        AlbumKind.entries.firstOrNull { it.name == this && it != AlbumKind.Device }

    private fun kindOfId(id: String): AlbumKind = when {
        id.startsWith(FOLDER_PREFIX) -> AlbumKind.Folder
        id.startsWith(TAGS_PREFIX) -> AlbumKind.Tags
        else -> AlbumKind.Smart
    }

    companion object {
        const val SMART_PREFIX = "smart:"
        const val FOLDER_PREFIX = "folder:"
        const val TAGS_PREFIX = "tag:"
        const val MAX_SMART_MEMBERS = 800

        /** Folder albums are keyed by the folder they mirror, so one folder cannot yield two. */
        fun folderIdFor(folderPath: String): String = FOLDER_PREFIX + folderPath

        fun smartId(): String = SMART_PREFIX + UUID.randomUUID().toString()

        fun tagsId(): String = TAGS_PREFIX + UUID.randomUUID().toString()

        /** Unchanged names on purpose: these are the on-disk format of every existing install. */
        private const val PrefsName = "smart_albums"
        private const val KeyAlbums = "smart_album_data"
    }
}
