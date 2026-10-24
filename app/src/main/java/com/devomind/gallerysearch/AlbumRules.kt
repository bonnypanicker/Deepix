package com.devomind.gallerysearch

import com.devomind.gallerysearch.db.TagEntity

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
}
