package com.devomind.gallerysearch

/**
 * The single place media lists get ordered. Pure and stateless — call it from a
 * background dispatcher; on a large library this is the only expensive step.
 *
 * Every order ends with a URI tiebreaker so it is a *total* order. Without that,
 * items sharing a key (very common for size and name) come back in arbitrary order
 * between renders, which invalidates the adapter's stable IDs and makes thumbnails
 * visibly churn.
 */
object MediaSorter {

    fun sort(
        items: List<GalleryRepository.MediaItem>,
        option: SortOption
    ): List<GalleryRepository.MediaItem> {
        if (items.size < 2) return items
        return when (option) {
            SortOption.NameAsc -> sortByName(items, descending = false)
            SortOption.NameDesc -> sortByName(items, descending = true)
            SortOption.NewestFirst -> items.sortedWith(byLong(descending = true) { it.dateMillis })
            SortOption.OldestFirst -> items.sortedWith(byLong(descending = false) { it.dateMillis })
            SortOption.LargestFirst -> items.sortedWith(byLong(descending = true) { it.sizeBytes })
            SortOption.SmallestFirst -> items.sortedWith(byLong(descending = false) { it.sizeBytes })
            SortOption.RecentlyModified -> items.sortedWith(byLong(descending = true) { modifiedOf(it) })
            SortOption.LeastRecentlyModified -> items.sortedWith(byLong(descending = false) { modifiedOf(it) })
        }
    }

    private inline fun byLong(
        descending: Boolean,
        crossinline selector: (GalleryRepository.MediaItem) -> Long
    ): Comparator<GalleryRepository.MediaItem> = Comparator { a, b ->
        val cmp = selector(a).compareTo(selector(b))
        val ordered = if (descending) -cmp else cmp
        if (ordered != 0) ordered else a.uri.toString().compareTo(b.uri.toString())
    }

    /**
     * Extracts each display name once instead of re-deriving it inside the comparator,
     * which on a 100k-item library saves a six-figure number of string operations.
     */
    private fun sortByName(
        items: List<GalleryRepository.MediaItem>,
        descending: Boolean
    ): List<GalleryRepository.MediaItem> {
        val keyed = items.map { nameOf(it) to it }
        val comparator = Comparator<Pair<String, GalleryRepository.MediaItem>> { a, b ->
            val byName = naturalCompare(a.first, b.first)
            if (byName != 0) {
                if (descending) -byName else byName
            } else {
                a.second.uri.toString().compareTo(b.second.uri.toString())
            }
        }
        return keyed.sortedWith(comparator).map { it.second }
    }

    private fun nameOf(item: GalleryRepository.MediaItem): String {
        item.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        return item.path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: ""
    }

    /** MediaStore can report 0 for DATE_MODIFIED; fall back so those items don't all clump. */
    private fun modifiedOf(item: GalleryRepository.MediaItem): Long =
        if (item.dateModifiedMillis > 0L) item.dateModifiedMillis else item.dateMillis

    /**
     * Compares digit runs numerically so "IMG_2" sorts before "IMG_10". Case-insensitive,
     * matching how a file manager orders names.
     */
    private fun naturalCompare(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left[i]
            val b = right[j]
            if (a.isDigit() && b.isDigit()) {
                val aEnd = digitRunEnd(left, i)
                val bEnd = digitRunEnd(right, j)
                val cmp = compareDigitRuns(left, i, aEnd, right, j, bEnd)
                if (cmp != 0) return cmp
                i = aEnd
                j = bEnd
            } else {
                val cmp = a.lowercaseChar().compareTo(b.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (left.length - i).compareTo(right.length - j)
    }

    private fun digitRunEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && text[end].isDigit()) end++
        return end
    }

    /** Compares two digit runs by value without parsing, so arbitrarily long runs are safe. */
    private fun compareDigitRuns(
        left: String,
        leftFrom: Int,
        leftEnd: Int,
        right: String,
        rightFrom: Int,
        rightEnd: Int
    ): Int {
        var a = leftFrom
        var b = rightFrom
        while (a < leftEnd && left[a] == '0') a++
        while (b < rightEnd && right[b] == '0') b++
        val aLen = leftEnd - a
        val bLen = rightEnd - b
        if (aLen != bLen) return aLen - bLen
        for (offset in 0 until aLen) {
            val cmp = left[a + offset].compareTo(right[b + offset])
            if (cmp != 0) return cmp
        }
        return 0
    }
}
