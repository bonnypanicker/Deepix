package com.devomind.gallerysearch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * What keeps the only reachable copy of a vault alive.
 *
 * After a reinstall, or whenever a picked folder or file goes out of reach, the app-private mirror
 * can be the *only* copy of photos the user has already removed from the gallery. A refresh must
 * never throw one away, and a commit must never report success on a push that did not land — those
 * two rules are the whole point of [VaultMirror], and of the `.bak` a picked-document push keeps
 * until the write is verified.
 */
class VaultMirrorTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- refresh: the mirror is never sacrificed to a remote that is not there ----

    @Test
    fun aRemoteThatIsNotThereIsNotAPull() {
        val local = File(temp.root, VAULT_ARCHIVE_NAME).apply { writeBytes(ByteArray(64)) }

        assertFalse(
            "an absent remote (null size) must leave the mirror exactly as it is",
            VaultMirror.needsPull(local, null)
        )
    }

    @Test
    fun aRemoteOfTheSameSizeIsNotPulledTwice() {
        val local = File(temp.root, VAULT_ARCHIVE_NAME).apply { writeBytes(ByteArray(64)) }

        assertFalse(VaultMirror.needsPull(local, 64))
        assertTrue("a different length means the remote moved on", VaultMirror.needsPull(local, 65))
        assertTrue(
            "no mirror at all always has to be pulled",
            VaultMirror.needsPull(File(temp.root, "absent"), 64)
        )
    }

    @Test
    fun aMissingDocumentLeavesTheMirrorAlone() {
        val dir = vaultMirrorDir()
        val mirror = archiveOf(dir)
        val before = mirror.readBytes()
        val storage = PickedDocumentVaultStorage(dir, FakeDocument(present = false))

        storage.refresh()

        assertArrayEquals("the mirror may be the only copy of the photos", before, mirror.readBytes())
    }

    @Test
    fun aMirrorLostWithItsBackupBesideItIsRebuilt() {
        val dir = vaultMirrorDir()
        val mirror = archiveOf(dir)
        val before = mirror.readBytes()
        val backup = backupOf(dir).apply { writeBytes(before) }
        mirror.delete()
        val storage = PickedDocumentVaultStorage(dir, FakeDocument(present = false))

        storage.refresh()

        assertArrayEquals(before, mirror.readBytes())
        assertTrue(backup.exists())
    }

    // ---- commit: a push is only settled once the document reports the whole archive ----

    @Test
    fun aPushThatLandsShortKeepsTheMirrorAndMarksTheVaultDirty() {
        val dir = vaultMirrorDir()
        val mirror = archiveOf(dir)
        val before = mirror.readBytes()
        val document = FakeDocument(reportedSize = 5)
        val storage = PickedDocumentVaultStorage(dir, document)

        val failure = runCatching { storage.commit() }.exceptionOrNull()

        assertTrue("a short push must be reported, not swallowed", failure is IOException)
        assertArrayEquals("the mirror still holds every photo", before, mirror.readBytes())
        assertTrue("the next unlock has to push it again", document.dirty)
        assertTrue(
            "the last known-good copy stays until the push is verified",
            backupOf(dir).exists()
        )
    }

    @Test
    fun aDocumentThatCannotBeWrittenToRefusesThePush() {
        val dir = vaultMirrorDir()
        val mirror = archiveOf(dir)
        val before = mirror.readBytes()
        val document = FakeDocument(writable = false)
        val storage = PickedDocumentVaultStorage(dir, document)

        val failure = runCatching { storage.commit() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertArrayEquals(before, mirror.readBytes())
        assertFalse("nothing was written, so nothing is owed a re-push", document.dirty)
        assertEquals(null, document.written)
    }

    @Test
    fun aPushThatLandsWholeClearsTheDirtyFlagAndDropsTheBackup() {
        val dir = vaultMirrorDir()
        val mirror = archiveOf(dir)
        val document = FakeDocument(reportedSize = mirror.length())
        val storage = PickedDocumentVaultStorage(dir, document)

        storage.commit()

        assertArrayEquals("the document got the archive byte for byte", mirror.readBytes(), document.written)
        assertFalse(document.dirty)
        assertFalse(backupOf(dir).exists())
    }

    @Test
    fun aVaultLeftDirtyIsPushedAgainOnTheNextRefresh() {
        val dir = vaultMirrorDir()
        val mirror = archiveOf(dir)
        val document = FakeDocument(dirty = true, reportedSize = mirror.length())
        val storage = PickedDocumentVaultStorage(dir, document)

        storage.refresh()

        assertArrayEquals(mirror.readBytes(), document.written)
        assertFalse("the retry landed, so the vault is clean again", document.dirty)
    }

    // ---- publication: a provider that renames leaves the app looking at nothing ----

    @Test
    fun anArchivePublishedUnderAnotherNameIsAFailedCommit() {
        assertTrue(VaultMirror.nameLanded(VAULT_ARCHIVE_NAME, VAULT_ARCHIVE_NAME))
        assertFalse(
            "MediaStore renames on collision; accepting that would hide the new archive",
            VaultMirror.nameLanded("PixaSafe (2).zip", VAULT_ARCHIVE_NAME)
        )
        assertTrue(
            "a provider that will not report a name at all is accepted",
            VaultMirror.nameLanded(null, VAULT_ARCHIVE_NAME)
        )
    }

    @Test
    fun anInvisibleArchiveIsNotOverwritten() {
        val taken = setOf(VAULT_ARCHIVE_NAME, "PixaSafe (2).zip")

        assertEquals("PixaSafe (3).zip", VaultMirror.uniqueArchiveName(taken))
        assertEquals("PixaSafe (2).zip", VaultMirror.uniqueArchiveName(emptySet()))
    }

    // ---- fixtures ----

    /**
     * A mirror directory holding a real archive with one real entry, so a truncated push cannot pass
     * for a whole one.
     */
    private fun vaultMirrorDir(): File {
        val dir = temp.newFolder("mirror")
        val zip = archiveOf(dir)
        SafeCrypto.createEmptyArchive(zip)
        val source = File(dir, "photo.jpg").apply { writeBytes(ByteArray(2048) { 7 }) }
        SafeCrypto.addFileToZip(zip, source, "photo.jpg", PASSWORD.toCharArray())
        source.delete()
        return dir
    }

    private fun archiveOf(dir: File): File = File(dir, VAULT_ARCHIVE_NAME)

    private fun backupOf(dir: File): File = File(dir, VAULT_ARCHIVE_NAME + ".bak")

    private class FakeDocument(
        override var dirty: Boolean = false,
        private val present: Boolean = true,
        private val writable: Boolean = true,
        private val reportedSize: Long = -1
    ) : VaultDocument {

        var written: ByteArray? = null

        override fun exists(): Boolean = present
        override fun size(): Long = reportedSize
        override fun writable(): Boolean = writable
        override fun readTo(target: File) {
            target.writeBytes(ByteArray(16))
        }

        override fun writeFrom(source: File) {
            written = source.readBytes()
        }

        override fun truncate() {
            written = null
        }
    }

    private companion object {
        const val PASSWORD = "correct horse"
    }
}
