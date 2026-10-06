package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What happens to a photo when the process dies at each step of a bin move.
 *
 * Each test lays out the files a crash would leave — sidecar, partial or whole copy, original — and
 * then asks the two questions [BinLedger.reconcile] asks. The invariant under all of them, and the
 * reason the ledger exists, is that a photo ends up either in its original folder or whole in the bin.
 */
class BinLedgerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File by lazy { File(folder.root, "bin").apply { mkdirs() } }

    /** A photo sitting in its real folder, as it was before the delete. */
    private fun original(name: String = "IMG_0001.jpg", bytes: Int = 100): File =
        File(folder.root, name).apply { writeBytes(ByteArray(bytes) { 'a'.code.toByte() }) }

    private fun pending(entry: BinEntry) {
        assertTrue(BinLedger.write(root, entry))
    }

    private fun dataBytes(id: String, size: Int) {
        BinLedger.dataDir(root).mkdirs()
        BinLedger.dataFile(root, id).writeBytes(ByteArray(size) { 'a'.code.toByte() })
    }

    private fun entryOf(
        id: String = "100_IMG.jpg",
        state: BinState,
        path: String?,
        sizeBytes: Long
    ) = BinEntry(
        id = id,
        fileName = "IMG.jpg",
        originalPath = path,
        mimeType = "image/jpeg",
        deletedAt = 5_000L,
        sizeBytes = sizeBytes,
        state = state
    )

    private fun reconcile() = BinLedger.reconcile(root, BinLedger::originalStillThere)

    @Test
    fun aRecordRoundTripsEveryField() {
        val source = original(bytes = 100)
        val entry = entryOf(state = BinState.Pending, path = source.absolutePath, sizeBytes = 100L)
        pending(entry)
        assertEquals(entry, BinLedger.read(BinLedger.sidecarFile(root, entry.id)))
    }

    @Test
    fun aCopyThatFinishedButWasNeverDeletedBecomesAnEntry() {
        val id = "100_IMG.jpg"
        pending(entryOf(id = id, state = BinState.Pending, path = "/gone/IMG_0001.jpg", sizeBytes = 100L))
        dataBytes(id, 100)

        val result = reconcile()

        assertEquals(1, result.promoted)
        assertEquals(listOf(id), BinLedger.list(root).map { it.id })
        assertEquals(BinState.Committed, BinLedger.list(root).first().state)
    }

    /** The delete step itself was interrupted: the photo is still in the gallery, so the bin is not. */
    @Test
    fun aCopyThatFinishedWhileTheOriginalStandsIsRolledBack() {
        val id = "100_IMG.jpg"
        val source = original()
        pending(entryOf(id = id, state = BinState.Pending, path = source.absolutePath, sizeBytes = 100L))
        dataBytes(id, 100)

        val result = reconcile()

        assertEquals(1, result.rolledBack)
        assertTrue("the photo stays where it was", source.isFile)
        assertFalse("the redundant copy is gone", BinLedger.dataFile(root, id).exists())
        assertTrue(BinLedger.list(root).isEmpty())
    }

    /** Killed after the record said Committed but before the original came off the disk. */
    @Test
    fun aCommittedEntryWhoseOriginalSurvivedIsRolledBack() {
        val id = "100_IMG.jpg"
        val source = original()
        pending(entryOf(id = id, state = BinState.Committed, path = source.absolutePath, sizeBytes = 100L))
        dataBytes(id, 100)

        val result = reconcile()

        assertEquals(1, result.rolledBack)
        assertTrue(source.isFile)
        assertFalse(BinLedger.sidecarFile(root, id).exists())
    }

    /** A recycled file name at the original path is a different photo, not the original coming back. */
    @Test
    fun aDifferentFileUnderTheSamePathDoesNotRollTheEntryBack() {
        val id = "100_IMG.jpg"
        val replaced = original(bytes = 40)
        pending(entryOf(id = id, state = BinState.Committed, path = replaced.absolutePath, sizeBytes = 100L))
        dataBytes(id, 100)

        reconcile()

        assertTrue("the bin copy is the only complete one", BinLedger.dataFile(root, id).exists())
        assertEquals(listOf(id), BinLedger.list(root).map { it.id })
    }

    @Test
    fun aTruncatedCopyWithTheOriginalStillPresentIsDiscarded() {
        val id = "100_IMG.jpg"
        val source = original()
        pending(entryOf(id = id, state = BinState.Pending, path = source.absolutePath, sizeBytes = 100L))
        dataBytes(id, 30)

        val result = reconcile()

        assertEquals(1, result.rolledBack)
        assertTrue(source.isFile)
        assertFalse(BinLedger.dataFile(root, id).exists())
    }

    /**
     * The worst case: the original is gone and the copy is only part of the file. The bytes are all
     * that exists, so they are kept and the record is renamed aside — never reported as an entry,
     * never silently deleted.
     */
    @Test
    fun aTruncatedCopyWithNoOriginalKeepsItsBytesAndStopsBeingAnEntry() {
        val id = "100_IMG.jpg"
        pending(entryOf(id = id, state = BinState.Pending, path = "/gone/IMG_0001.jpg", sizeBytes = 100L))
        dataBytes(id, 30)

        val result = reconcile()

        assertEquals(1, result.quarantined)
        assertTrue("bytes retained", BinLedger.dataFile(root, id).isFile)
        assertFalse("record retired", BinLedger.sidecarFile(root, id).exists())
        assertTrue(
            "record parked aside, not deleted",
            File(BinLedger.entriesDir(root), "$id${BinLedger.CorruptSuffix}").isFile
        )
        assertTrue("not offered as a photo", BinLedger.list(root).isEmpty())
    }

    @Test
    fun anUnreadableRecordIsRenamedAsideAndItsBytesAreAdopted() {
        val id = "100_IMG.jpg"
        BinLedger.entriesDir(root).mkdirs()
        BinLedger.sidecarFile(root, id).writeText("½ of a record")
        dataBytes(id, 100)

        val result = reconcile()

        assertEquals(1, result.quarantined)
        assertEquals(1, result.adopted)
        val adopted = BinLedger.list(root).single()
        assertEquals(id, adopted.id)
        assertEquals(BinState.Recovered, adopted.state)
        assertNull("its folder is unknown, so a restore must not guess one", adopted.originalPath)
    }

    @Test
    fun bytesWithNoRecordAreAdoptedAsRecovered() {
        dataBytes("stray", 64)

        val result = reconcile()

        assertEquals(1, result.adopted)
        val adopted = BinLedger.list(root).single()
        assertEquals(64L, adopted.sizeBytes)
        assertEquals(BinState.Recovered, adopted.state)
    }

    /** Both halves of the photo are gone already; the record describes nothing and is dropped. */
    @Test
    fun aRecordWithNoBytesAnywhereIsDiscarded() {
        val id = "100_IMG.jpg"
        pending(entryOf(id = id, state = BinState.Committed, path = "/gone/IMG_0001.jpg", sizeBytes = 100L))

        val result = reconcile()

        assertEquals(1, result.discarded)
        assertFalse(BinLedger.sidecarFile(root, id).exists())
    }

    /** A loose file from the pre-sidecar layout is pulled into the data dir and stays listed. */
    @Test
    fun aLoosePhotoFromTheOldLayoutIsClaimed() {
        val id = "100_IMG.jpg"
        File(root, id).writeBytes(ByteArray(100) { 'a'.code.toByte() })
        pending(entryOf(id = id, state = BinState.Committed, path = "/gone/IMG_0001.jpg", sizeBytes = 100L))

        reconcile()

        assertNotNull(BinLedger.claimData(root, id))
        assertFalse("moved out of the bin root", File(root, id).exists())
        assertEquals(listOf(id), BinLedger.list(root).map { it.id })
    }

    @Test
    fun reconcilingTwiceChangesNothingTheSecondTime() {
        val id = "100_IMG.jpg"
        pending(entryOf(id = id, state = BinState.Pending, path = "/gone/IMG_0001.jpg", sizeBytes = 100L))
        dataBytes(id, 100)
        reconcile()

        val second = reconcile()

        assertEquals(BinLedger.Reconciled(), second)
        assertEquals(1, BinLedger.list(root).size)
    }

    @Test
    fun aCopyIsOnlyWholeWhenItHoldsEveryByte() {
        assertFalse(BinLedger.copyComplete(expectedBytes = 100L, writtenBytes = 99L))
        assertTrue(BinLedger.copyComplete(expectedBytes = 100L, writtenBytes = 100L))
        // An unknown source size proves only that something arrived; reconcile re-checks it later.
        assertTrue(BinLedger.copyComplete(expectedBytes = 0L, writtenBytes = 1L))
        assertFalse(BinLedger.copyComplete(expectedBytes = 0L, writtenBytes = 0L))
    }

    @Test
    fun theLastBlocksAreReserved() {
        assertTrue(BinLedger.hasRoomFor(usableBytes = 200L, neededBytes = 100L, marginBytes = 50L))
        assertFalse(BinLedger.hasRoomFor(usableBytes = 140L, neededBytes = 100L, marginBytes = 50L))
        assertFalse(BinLedger.hasRoomFor(usableBytes = 40L, neededBytes = 0L, marginBytes = 50L))
    }

    /** Discarding must report a refused delete rather than assume one. */
    @Test
    fun discardingRemovesBothHalvesOfAnEntry() {
        val id = "100_IMG.jpg"
        pending(entryOf(id = id, state = BinState.Committed, path = "/gone/IMG_0001.jpg", sizeBytes = 100L))
        dataBytes(id, 100)

        assertTrue(BinLedger.discard(root, id))
        assertFalse(BinLedger.dataFile(root, id).exists())
        assertFalse(BinLedger.sidecarFile(root, id).exists())
        // Nothing left to delete is still a success: the photo is not in the bin, which was the goal.
        assertTrue(BinLedger.discard(root, id))
    }

    @Test
    fun discardingReportsAPhotoItCouldNotRemove() {
        val id = "100_IMG.jpg"
        pending(entryOf(id = id, state = BinState.Committed, path = "/gone/IMG_0001.jpg", sizeBytes = 100L))
        // Something occupying the photo's path that a file delete cannot take down.
        val occupied = BinLedger.dataFile(root, id)
        assertTrue(occupied.mkdirs())
        File(occupied, "child").writeText("x")

        assertFalse(BinLedger.discard(root, id))
    }
}
