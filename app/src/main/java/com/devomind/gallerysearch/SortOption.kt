package com.devomind.gallerysearch

/**
 * The sort orders offered on every media listing.
 *
 * [key] is the persisted identifier — never change one, or saved preferences silently
 * reset to the default. [dateOrdered] drives whether the timeline emits month/day
 * headers: they only make sense when the list is actually in date order.
 *
 * Adding a new order (resolution, duration, favourites first) means adding a constant
 * here and a branch in [MediaSorter]; no listing screen needs to change.
 */
enum class SortOption(val key: String, val label: String, val dateOrdered: Boolean) {
    NewestFirst("date_desc", "Newest first", true),
    OldestFirst("date_asc", "Oldest first", true),
    NameAsc("name_asc", "Name (A–Z)", false),
    NameDesc("name_desc", "Name (Z–A)", false),
    LargestFirst("size_desc", "Largest first", false),
    SmallestFirst("size_asc", "Smallest first", false),
    RecentlyModified("modified_desc", "Recently modified", false),
    LeastRecentlyModified("modified_asc", "Least recently modified", false);

    companion object {
        val DEFAULT = NewestFirst

        fun fromKey(key: String?): SortOption = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
