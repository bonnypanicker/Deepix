package com.devomind.gallerysearch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Removing one photo from the Safe.
 *
 * The vault is the only copy of every photo already taken out of the gallery, so a removal has to
 * settle as a whole archive: the entry goes from a readable copy, the folder map loses it in the
 * same write, and only then does that copy replace the live vault. A removal that matches nothing
 * must say so instead of reporting success and changing nothing (zip4j does exactly that when a
 * name it was given is not in the archive).
 */
class SafeRemovalTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val password = "hunter2".toCharArray()

    private fun vaultWith(vararg entries: Pair<String, Int>): File {
        val zip = File(temp.root, VAULT_ARCHIVE_NAME)
        SafeCrypto.createEmptyArchive(zip)
        entries.forEach { (name, size) ->
            val source = File(temp.root, "source_" + name.replace('/', '_')).apply {
                writeBytes(ByteArray(size) { (it % 251).toByte() })
            }
            SafeCrypto.addFileToZip(zip, source, name, password)
        }
        return zip
    }

    private fun mapEntry(zip: File, json: String) {
        val source = File(temp.root, "original_paths.json").apply { writeText(json) }
        SafeCrypto.addFileToZip(zip, source, ".pixa/original_paths.json", password)
    }

    @Test
    fun removingAPhotoDropsItAndItsOriginalFolderTogether() {
        val zip = vaultWith(
            "Camera/a.jpg" to 2_048,
            "Camera/b.jpg" to 4_096,
            "Trips/c.jpg" to 1_024
        )
        mapEntry(
            zip,
            """{"Camera/a.jpg":"/sdcard/DCIM/Camera","Camera/b.jpg":"/sdcard/DCIM/Camera",""" +
                """"Trips/c.jpg":"/sdcard/Pictures/Trips"}"""
        )
        val kept = extract(zip, "Camera/b.jpg")

        val settled = SafeCrypto.removeEntrySettled(zip, "Camera/a.jpg", password) { target ->
            // What SafeManager.writeOriginalPaths does on the archive being settled.
            SafeCrypto.removeEntry(target, ".pixa/original_paths.json", password)
            val rewritten = File(temp.root, "map.json").apply {
                writeText("""{"Camera/b.jpg":"/sdcard/DCIM/Camera","Trips/c.jpg":"/sdcard/Pictures/Trips"}""")
            }
            SafeCrypto.addFileToZip(target, rewritten, ".pixa/original_paths.json", password)
        }

        assertTrue("a photo that is in the vault settles", settled)
        assertEquals(
            listOf("Camera/b.jpg", "Trips/c.jpg", ".pixa/original_paths.json"),
            SafeCrypto.listEntryNames(zip)
        )
        assertArrayEquals("the photos that stayed must still decrypt", kept, extract(zip, "Camera/b.jpg"))

        val mapDir = File(temp.root, "map_read").apply { mkdirs() }
        SafeCrypto.extractEntry(zip, ".pixa/original_paths.json", password, mapDir)
        val mapText = File(mapDir, ".pixa/original_paths.json").readText()
        assertFalse("the removed photo must not keep a restore folder", "Camera/a.jpg" in mapText)
        assertTrue("the other folders stay", "Trips/c.jpg" in mapText)
    }

    @Test
    fun aNameThatIsNotInTheVaultIsNotReportedAsRemoved() {
        val zip = vaultWith("Camera/a.jpg" to 2_048)
        val before = zip.readBytes()

        // zip4j's removeFile() succeeds silently for a name it never matched; that is the shape of
        // a removal the user taps and the grid keeps showing.
        assertFalse(
            "an unmatched name must fail loudly, not pass for a removal",
            SafeCrypto.removeEntrySettled(zip, "Camera/missing.jpg", password)
        )
        assertEquals(listOf("Camera/a.jpg"), SafeCrypto.listEntryNames(zip))
        assertArrayEquals("a failed removal never touches the live vault", before, zip.readBytes())
    }

    @Test
    fun aMapRewriteThatCannotBeReadBackLeavesTheLiveVaultAlone() {
        val zip = vaultWith("Camera/a.jpg" to 2_048, "Camera/b.jpg" to 2_048)
        val before = zip.readBytes()

        val settled = SafeCrypto.removeEntrySettled(zip, "Camera/a.jpg", password) { target ->
            // A rewrite that tears the archive must be caught before it can become the vault.
            target.writeBytes(ByteArray(32))
        }

        assertFalse("a torn rewrite is not a settled removal", settled)
        assertArrayEquals("the vault still holds every photo", before, zip.readBytes())
        assertEquals(listOf("Camera/a.jpg", "Camera/b.jpg"), SafeCrypto.listEntryNames(zip))
    }

    @Test
    fun noCopyOfTheVaultIsLeftBehindAfterASettledRemoval() {
        val zip = vaultWith("Camera/a.jpg" to 2_048, "Camera/b.jpg" to 2_048)

        assertTrue(SafeCrypto.removeEntrySettled(zip, "Camera/a.jpg", password))
        assertEquals(
            "the sibling copy the removal was built on must be gone",
            listOf(VAULT_ARCHIVE_NAME),
            temp.root.listFiles()!!.map { it.name }.filter { it.startsWith(VAULT_ARCHIVE_NAME) }
        )
    }

    private fun extract(zip: File, entryName: String): ByteArray {
        val dir = File(temp.root, "read_" + System.nanoTime()).apply { mkdirs() }
        SafeCrypto.extractEntry(zip, entryName, password, dir)
        return File(dir, entryName).readBytes()
    }
}
