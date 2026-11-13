package com.devomind.gallerysearch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contracts for showing the saved edit. The activity result is the only channel between an
 * editor and the viewer, so these pin the shape of that hand-off and the viewer's reply to it.
 */
class EditorSavedResultTest {

    @Test
    fun editorsReportTheFileTheirSaveWrote() {
        val photo = source("PhotoEditorActivity.kt")
        assertTrue(
            "Save in place must report the original it rewrote",
            "finishSaved(sourceUri, replacedOriginal = true)" in photo
        )
        assertTrue(
            "Save a copy must report the copy it inserted",
            "finishSaved(savedUri, replacedOriginal = false)" in photo
        )

        val video = source("VideoEditorActivity.kt")
        assertTrue(
            "Save in place must report the original it rewrote",
            "finishSaved(sourceUri, replacedOriginal = true)" in video
        )
        assertTrue(
            "Save a copy must report the copy it inserted",
            "finishSaved(savedUri, replacedOriginal = false)" in video
        )
    }

    @Test
    fun viewerShowsTheSavedItem() {
        val viewer = source("ViewerActivity.kt")
        assertTrue(
            "An in-place save must force the current page to decode the rewritten file",
            "showEditedOriginal(savedUri)" in viewer && "adapter.refresh(index)" in viewer
        )
        assertTrue(
            "A saved copy must be resolved through MediaStore and opened",
            "showSavedCopy(savedUri)" in viewer &&
                "getImageItemsForUris(listOf(savedUri.toString()))" in viewer &&
                "getVideoItemsForUris(listOf(savedUri.toString()))" in viewer
        )
    }

    @Test
    fun pagerCanRebindAnUnchangedUri() {
        val adapter = source("MediaPagerAdapter.kt")
        assertTrue(
            "A plain notifyItemChanged is swallowed by the same-Uri bind guard",
            "fun refresh(position: Int)" in adapter &&
                "notifyItemChanged(position, ReloadPayload)" in adapter
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
