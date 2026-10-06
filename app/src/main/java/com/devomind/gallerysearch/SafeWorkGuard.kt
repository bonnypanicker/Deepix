package com.devomind.gallerysearch

/**
 * Counts the vault writes in flight so a lock can't land in the middle of one.
 *
 * Locking wipes the Safe's plaintext staging directories, and an import writes the photo through one
 * of those directories into the archive. A wipe between the copy and the archive append used to leave
 * a half-written entry in `DeepixSafe.zip` — which is the only copy of every photo already imported.
 * So a lock requested while work is running is remembered, and the last worker to finish performs it.
 *
 * Kept separate from [SafeManager] (and free of Android) so the deferral itself is unit-testable.
 */
class SafeWorkGuard {

    private val monitor = Any()

    private var inFlight = 0

    private var lockRequested = false

    val isBusy: Boolean get() = synchronized(monitor) { inFlight > 0 }

    fun begin() {
        synchronized(monitor) { inFlight++ }
    }

    /**
     * Signals that one piece of work finished.
     *
     * @return true when this was the last writer standing and a lock was asked for meanwhile — the
     *         caller must perform it.
     */
    fun end(): Boolean = synchronized(monitor) {
        check(inFlight > 0) { "end() called with no work in flight" }
        inFlight--
        if (inFlight == 0 && lockRequested) {
            lockRequested = false
            true
        } else {
            false
        }
    }

    /**
     * @return true when the lock should be carried out now, false when a vault write is running and
     *         the caller must leave the lock to [end].
     */
    fun requestLock(): Boolean = synchronized(monitor) {
        if (inFlight > 0) {
            lockRequested = true
            false
        } else {
            true
        }
    }

    /** A fresh unlock means the user is back inside the vault; a parked lock request no longer applies. */
    fun cancelPendingLock() {
        synchronized(monitor) { lockRequested = false }
    }
}
