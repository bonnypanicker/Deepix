package com.devomind.gallerysearch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for when an editor's save becomes reachable. Saving is destructive (Save rewrites
 * the original), so it must not be possible before the user has confirmed an edit: the tick applies
 * the tool's work, and only then may the save menu open.
 */
class EditorSaveGateTest {

    @Test
    fun thePhotoEditorActivatesSaveOnlyAfterATickConfirmsAnEdit() {
        val editor = source("PhotoEditorActivity.kt")
        assertTrue(
            "Save must be live only once an op is confirmed and no tool is holding a pending one",
            "binding.saveBtn.isEnabled = ops.isNotEmpty() && tool == null" in editor
        )
        assertTrue(
            "The gate must be re-evaluated when an op is added, undone, or a tool opens or closes",
            Regex("syncSaveButton\\(\\)").findAll(editor).count() >= 5
        )
        assertTrue(
            "showSaveMenu itself must refuse to save with nothing confirmed",
            "if (working == null || ops.isEmpty()) return" in editor
        )
    }

    @Test
    fun theVideoEditorAlreadyRefusesASaveWithoutChanges() {
        val video = source("VideoEditorActivity.kt")
        assertTrue(
            "The video editor gates its save menu on the edited flag",
            "if (!edited)" in video
        )
    }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/PhotoEditorActivity.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
