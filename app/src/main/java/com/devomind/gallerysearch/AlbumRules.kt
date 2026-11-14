package com.devomind.gallerysearch

import android.net.Uri
import com.devomind.gallerysearch.db.TagEntity
import java.util.Locale

/**
 * How a folder or tag album decides what belongs to it.
 *
 * Pure on purpose: these are the rules the albums exist to remember, and they are the parts a wrong
 * edge case would show up as an album full of the wrong photos. Nothing here touches the library, a
 * Context or a Uri — the caller passes what it already holds in memory.
 */
object AlbumRules {

    /** Every album folder the app itself makes lives inside this one. */
    const val AlbumFolderParent: String = "Pixa Albums"

    /**
     * The folder a new album owns: `<storage root>/<parent>/<name>`.
     *
     * The name is what the user typed and becomes a real directory, so it is cleaned rather than
     * trusted: separators and control characters go, `.` and `..` cannot name a folder, a name that is
     * only punctuation yields null, and the result cannot climb out of [parent] no matter what was
     * typed. Length is capped because a MediaStore `RELATIVE_PATH` is a column, not a bottomless one.
     */
    fun albumFolderPath(root: String, parent: String, name: String): String? {
        val leaf = name.map { if (it == '/' || it == '\\' || it == ':' || it < ' ') ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
        if (leaf.isEmpty()) return null
        return "$root/$parent/${leaf.take(MaxAlbumFolderNameChars)}"
    }

    /**
     * Whether [path] is a folder an album of the app's own made, as opposed to one on the device that an
     * older album was pointed at. It is the difference between an empty album waiting to be filled and
     * a folder whose photos have all gone away — the first is what the user expects, the second is news.
     */
    fun isAlbumFolderPath(path: String): Boolean {
        val parent = path.substringBeforeLast('/').substringAfterLast('/')
        return parent == AlbumFolderParent || parent == "Deepix Albums"
    }

    /**
     * The storage-relative root a picked document tree points at, or null when it is not on primary
     * storage or is the whole volume.
     *
     * A tree's id arrives as `primary:Pictures/Trips`, and MediaStore's RELATIVE_PATH wants exactly the
     * part after the colon — so the folder the user picked becomes the parent [albumFolderPath] makes
     * the album's own folder under.
     */
    fun albumRootFromTreeDocId(docId: String?): String? {
        val parts = docId?.split(":", limit = 2) ?: return null
        if (parts.size != 2 || parts[0] != "primary") return null
        return parts[1].trim('/').takeIf { it.isNotEmpty() }
    }

    /**
     * The folder a media row lives in, as storage-relative segments.
     *
     * `RELATIVE_PATH` already arrives relative and without the file name, while `DATA` (pre-Q) is an
     * absolute file path, so both shapes are normalised here: the file name is dropped when it matches
     * [displayName], and the volume prefix (`/storage/emulated/0/`, a card's `/storage/XXXX-XXXX/`,
     * `/sdcard/`) goes with it. An empty path falls back to the bucket, which is what MediaStore calls
     * the same folder.
     *
     * The volume patterns are compiled once, on purpose: the folder tree calls this once per media item,
     * and compiling three regexes per item is seconds of main-thread work on a large library. Only
     * absolute paths can carry a volume prefix, so the relative majority skips matching entirely.
     */
    fun folderSegments(path: String, bucketName: String, displayName: String?): List<String> {
        var dir = path.trim().replace('\\', '/')
        if (dir.isEmpty()) return listOf(bucketName)

        if (dir.endsWith("/")) dir = dir.dropLast(1)

        // DATA contains the filename; RELATIVE_PATH does not. Compare against DISPLAY_NAME rather
        // than treating every dotted directory as a file (folders such as "com.example" are valid).
        val lastSlash = dir.lastIndexOf('/')
        if (lastSlash >= 0) {
            val last = dir.substring(lastSlash + 1)
            if (!displayName.isNullOrBlank() && last.equals(displayName, ignoreCase = true)) {
                dir = dir.substring(0, lastSlash)
            }
        }

        if (dir.startsWith('/')) {
            dir = EmulatedVolumePrefix.replace(dir, "")
            dir = AnyVolumePrefix.replace(dir, "")
            dir = SdCardPrefix.replace(dir, "")
        }
        val segments = dir.trimStart('/').split('/').filter { it.isNotBlank() }
        return segments.ifEmpty { listOf(bucketName) }
    }

    /** A folder path as a comparable key: no trailing separator, no case difference. */
    fun folderKey(path: String): String = path.trim().lowercase(Locale.ROOT).trim('/')

    /**
     * The device albums a saved folder album already stands for.
     *
     * A folder album *is* the device folder it was made for, and once photos are put in it MediaStore
     * reports that folder as a bucket of its own. Both would then be listed — the saved album under
     * Pinned and its own folder under Others — so the bucket steps aside and the album represents it.
     * Any other device folder is left alone, even one that happens to share the name.
     */
    fun withoutMirroredFolders(
        deviceAlbums: List<GalleryRepository.Album>,
        savedAlbums: List<CollectionAlbum>
    ): List<GalleryRepository.Album> {
        val savedKeys = savedAlbums
            .asSequence()
            .filter { it.kind == AlbumKind.Folder }
            .map { folderKey(it.folderPath) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (savedKeys.isEmpty()) return deviceAlbums
        return deviceAlbums.filterNot { folderKey(it.folderPath) in savedKeys }
    }

    /**
     * What a folder album holds, folded from the device albums already in memory.
     *
     * This is the same count the folder tree would report — a bucket is a directory — but it costs one
     * pass over the album list instead of a pass over every photo, which is what keeps opening or
     * re-rendering the Albums page from walking the whole library. A folder album may cover subfolders,
     * so buckets beneath its path are included; the newest of them lends the cover.
     */
    fun folderContents(
        deviceAlbums: List<GalleryRepository.Album>,
        folderPath: String
    ): FolderContents {
        val key = folderKey(folderPath)
        if (key.isEmpty()) return FolderContents(0, null)
        val prefix = "$key/"
        val matches = deviceAlbums.filter {
            val candidate = folderKey(it.folderPath)
            candidate == key || candidate.startsWith(prefix)
        }
        if (matches.isEmpty()) return FolderContents(0, null)
        return FolderContents(
            count = matches.sumOf { it.count },
            coverUri = matches.maxByOrNull { it.latestMillis }?.coverUri
        )
    }

    /** A folder album's tile: how many photos it holds and which one to show. */
    data class FolderContents(val count: Int, val coverUri: Uri?)

    /**
     * The node for [path], or null when the library holds no such folder any more — which is what an
     * emptied folder's album renders as.
     */
    fun findFolderNode(roots: List<FolderNode>, path: String): FolderNode? {
        for (root in roots) {
            val found = findInTree(root, path)
            if (found != null) return found
        }
        return null
    }

    private fun findInTree(node: FolderNode, path: String): FolderNode? {
        if (node.path == path) return node
        for (child in node.children) {
            val found = findInTree(child, path)
            if (found != null) return found
        }
        return null
    }

    /**
     * The photos an album of [tagNames] collects: the union, so a photo carrying any one of the tags
     * belongs — the same rule the tag section of a search already applies.
     *
     * Names rather than ids, because ids are rowids a re-created tag can hand to something else, and
     * a tag that no longer exists simply contributes nothing.
     */
    fun tagUris(tagNames: List<String>, tags: List<TagEntity>, urisByTag: Map<Long, Set<String>>): Set<String> {
        if (tagNames.isEmpty()) return emptySet()
        val wanted = tagNames.toHashSet()
        val ids = tags.asSequence().filter { it.name in wanted }.map { it.id }
        return HashSet<String>().apply { ids.forEach { addAll(urisByTag[it].orEmpty()) } }
    }

    private const val MaxAlbumFolderNameChars = 60

    /** Volume prefixes of a MediaStore `DATA` path. Compiled once — see [folderSegments]. */
    private val EmulatedVolumePrefix = Regex("^/storage/emulated/\\d+/")
    private val AnyVolumePrefix = Regex("^/storage/[^/]+/")
    private val SdCardPrefix = Regex("^/sdcard/")
}
