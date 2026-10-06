package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The stage-then-rename shape the index checkpoints depend on.
 *
 * Host note: replacing a *live* base is Android's `rename(2)` behaviour and `fd.sync()` is supported on
 * the internal filesystem there, neither of which a desktop JVM guarantees. These tests therefore assert
 * what holds on both hosts: the bytes, the staging file's fate, and which outcome is reported.
 */
class IndexCheckpointTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val magic = 0xC0DE

    private fun target() = File(folder.root, "index.bin")

    private fun staging() = File(folder.root, "index.bin.tmp")

    private fun intsIn(file: File): List<Int> =
        DataInputStream(file.inputStream()).use { input ->
            val values = mutableListOf<Int>()
            while (input.available() >= 4) values.add(input.readInt())
            values
        }

    private fun seed(file: File, vararg values: Int) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            values.forEach { output.writeInt(it) }
        }
    }

    @Test
    fun aCheckpointReachesItsTargetAndLeavesNoStagingBehind() {
        val target = target()
        val staging = staging()
        val checkpoint = IndexCheckpoint.write(target, staging) { output ->
            output.writeInt(magic)
            output.writeInt(7)
        }
        assertTrue("the checkpoint should have landed: ${checkpoint.error}", checkpoint.replaced)
        assertFalse("staging must not outlive the rename", staging.exists())
        assertEquals(listOf(magic, 7), intsIn(target))
    }

    @Test
    fun aPayloadThatThrowsKeepsTheOldTargetAndDiscardsThePartialStaging() {
        val target = target()
        seed(target, magic, 3)
        val staging = staging()
        val boom = IOException("disk full mid-write")
        val checkpoint = IndexCheckpoint.write(target, staging) { output ->
            output.writeInt(magic)
            throw boom
        }
        assertFalse(checkpoint.replaced)
        assertEquals(boom, checkpoint.error)
        assertFalse("a partial checkpoint must not survive", staging.exists())
        assertEquals(listOf(magic, 3), intsIn(target))
    }

    /** A rename that cannot land must not swallow the snapshot: it stays staged, and promotable. */
    @Test
    fun aFailedRenameKeepsTheStagedSnapshot() {
        val target = target()
        assertTrue(target.mkdir())
        // Non-empty, so no host can move the staging file onto it: the desktop JVM refuses to delete
        // the directory it would replace, Android's rename(2) refuses to overwrite one.
        File(target, "anchor").createNewFile()
        val staging = staging()
        val checkpoint = IndexCheckpoint.write(target, staging) { output ->
            output.writeInt(magic)
            output.writeInt(1)
        }
        assertFalse(checkpoint.replaced)
        assertTrue(
            "expected a rename failure, got ${checkpoint.error}",
            checkpoint.error is CheckpointRenameFailed
        )
        assertTrue("staged checkpoint kept for promotion", staging.exists())
        assertEquals(listOf(magic, 1), intsIn(staging))
    }

    @Test
    fun promotionIsAskedForOnlyWhileTheBaseIsMissing() {
        val target = target()
        val staging = staging()
        seed(staging, magic, 1)

        assertTrue(IndexCheckpoint.promotable(target, staging))
        assertFalse(
            "no staging file, nothing to promote",
            IndexCheckpoint.promotable(target, File(folder.root, "absent.tmp"))
        )

        seed(target, magic, 2)
        assertFalse("a base that exists wins over a stale staging file", IndexCheckpoint.promotable(target, staging))
    }
}
