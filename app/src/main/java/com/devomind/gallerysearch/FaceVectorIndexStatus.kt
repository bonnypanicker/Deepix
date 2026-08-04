package com.devomind.gallerysearch

import android.content.Context

/**
 * Tiny SharedPreferences wrapper for the vector-index rebuild status. The Phase 3 maintenance
 * worker updates this from a background thread; the (Phase 4) maintenance UI reads it.
 *
 * States: OK → CORRUPTED → REBUILDING → OK. A rebuild retries from scratch if the next launch
 * comes up with status stuck in REBUILDING (process died mid-rebuild).
 */
object FaceVectorIndexStatus {

    private const val Prefs = "face_vector_index_status"
    private const val KeyState = "state"
    private const val KeyProcessed = "processed"
    private const val KeyTotal = "total"
    private const val KeyLastFinishedAt = "lastFinishedAt"

    enum class State { OK, CORRUPTED, REBUILDING, REBUILD_FAILED }

    data class Snapshot(
        val state: State,
        val processed: Int,
        val total: Int,
        val lastFinishedAt: Long
    )

    fun get(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(Prefs, Context.MODE_PRIVATE)
        val state = runCatching {
            State.valueOf(prefs.getString(KeyState, State.OK.name) ?: State.OK.name)
        }.getOrDefault(State.OK)
        // A REBUILDING state from a previous process is actually "failed mid-way" —
        // nobody updates it after a kill, so flip to REBUILD_FAILED so UI knows to re-run.
        val resolved = if (state == State.REBUILDING) State.REBUILD_FAILED else state
        return Snapshot(
            state = resolved,
            processed = prefs.getInt(KeyProcessed, 0),
            total = prefs.getInt(KeyTotal, 0),
            lastFinishedAt = prefs.getLong(KeyLastFinishedAt, 0L)
        )
    }

    fun setCorrupted(context: Context, total: Int) =
        putAll(context, State.CORRUPTED, 0, total)

    fun setRebuilding(context: Context, processed: Int, total: Int) =
        putAll(context, State.REBUILDING, processed, total)

    fun setOk(context: Context, finishedAt: Long = System.currentTimeMillis()) =
        putAll(context, State.OK, 0, 0, finishedAt)

    fun setFailed(context: Context) =
        putAll(context, State.REBUILD_FAILED, 0, 0)

    private fun putAll(context: Context, state: State, processed: Int, total: Int, finishedAt: Long = 0L) {
        context.getSharedPreferences(Prefs, Context.MODE_PRIVATE).edit()
            .putString(KeyState, state.name)
            .putInt(KeyProcessed, processed)
            .putInt(KeyTotal, total)
            .putLong(KeyLastFinishedAt, finishedAt)
            .apply()
    }
}
