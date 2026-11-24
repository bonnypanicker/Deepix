package com.devomind.gallerysearch

/**
 * Who closes the Safe screen while its vault is being written.
 *
 * Every vault mutation copies the archive, edits the copy and replaces the original. That rewrite is
 * blocking, so a screen going away does not stop it — it only discards its result. For an import
 * that is the worst of both worlds: the photos land in the vault *and* stay in the gallery, and a
 * new import can begin on an archive the old one is still replacing.
 *
 * So back during a write does not abandon it: the exit is parked here, and the last write to settle
 * asks for the screen to close ([settle]). Kept free of Android so the rule is unit-testable.
 */
class SafeLeaveGate {

    private val monitor = Any()

    private var writesInFlight = 0

    private var leaveRequested = false

    val isWriting: Boolean get() = synchronized(monitor) { writesInFlight > 0 }

    /** A vault write is starting; [settle] has to follow it, even when it fails. */
    fun begin() {
        synchronized(monitor) { writesInFlight++ }
    }

    /**
     * Signals that one write finished.
     *
     * @return true when this was the last write and the user has already asked to leave — the
     *         caller must close the screen now.
     */
    fun settle(): Boolean = synchronized(monitor) {
        check(writesInFlight > 0) { "settle() called with no vault write in flight" }
        writesInFlight--
        if (writesInFlight == 0 && leaveRequested) {
            leaveRequested = false
            true
        } else {
            false
        }
    }

    /**
     * @return true when the screen can close now, false when a write is running and the caller has
     *         to wait for [settle] to hand the exit back.
     */
    fun requestLeave(): Boolean = synchronized(monitor) {
        if (writesInFlight > 0) {
            leaveRequested = true
            false
        } else {
            true
        }
    }
}
