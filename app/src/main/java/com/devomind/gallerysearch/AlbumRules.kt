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

    /** One pickable entry in the folder chooser: a real folder on the device and what it holds. */
    data class FolderChoice(val path: String, val name: String, val itemCount: Int)

    /**
     * Every folder in the tree, at any depth, biggest first.
     *
     * A folder album is worth making for a folder that has photos in it, and the tree is built from
     * the library, so an empty folder cannot appear here — which is also the reason no album has to
     * be prepared for one.
     */
    fun folderChoices(roots: List<FolderNode>): List<FolderChoice> {
        val choices = mutableListOf<FolderChoice>()
        fun walk(node: FolderNode) {
            choices += FolderChoice(node.path, node.name, node.itemCount)
            node.children.forEach(::walk)
        }
        roots.forEach(::walk)
        return choices.sortedWith(
            compareByDescending<FolderChoice> { it.itemCount }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
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
}
