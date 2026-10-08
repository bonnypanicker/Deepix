package com.devomind.gallerysearch.db

/**
 * SQLite refuses to prepare a statement that binds more variables than its build allows, and every
 * device the app supports down to Android 8 ships the 999 cap. Room expands one `?` per element of an
 * `IN (:list)` argument — but a bulk `@Insert` expands one `?` per *column* per row, so a list of
 * thirteen-column rows reaches the ceiling at 76 photos while a uri list survives 999. The calls that
 * pass a whole library are exactly the ones a single chunk size cannot cover.
 *
 * So the pieces are sized from the row shape: a caller says how many variables one of its elements
 * binds, through the entity's own [BindVariables] constant, and gets chunks that fit.
 */
internal object RoomBatching {

    /** Under SQLite's 999, with the slack a query's own scalar arguments need. */
    const val MaxBoundVariables = 900

    /** Elements per statement when each element binds [variablesPerItem] variables. */
    fun chunkSizeFor(variablesPerItem: Int): Int =
        (MaxBoundVariables / variablesPerItem.coerceAtLeast(1)).coerceAtLeast(1)

    /**
     * [items] in pieces small enough for one statement to bind, in the caller's order. The default
     * fits an `IN (:list)` argument, whose elements bind one variable each.
     */
    fun <T> chunks(items: List<T>, variablesPerItem: Int = 1): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        return items.chunked(chunkSizeFor(variablesPerItem))
    }
}
