package com.devomind.gallerysearch

/**
 * The 64-bit key RecyclerView identifies a cell by across layouts and diffs.
 *
 * The key used to be built as one interpolated string — `"photo:$uri"` — and hashed from there. On
 * the scroll path that ran for every row: `getItemId` for stable-id bookkeeping and twice per
 * comparison inside DiffUtil, so each call allocated a fresh string for a value that never changes.
 * Folding the parts in place produces the same number without materialising the key.
 */
internal object StableId {

    private const val SEED = 1125899906842597L

    fun of(vararg parts: String): Long {
        var hash = SEED
        for (part in parts) {
            for (char in part) {
                hash = 31L * hash + char.code
            }
        }
        return hash
    }
}
