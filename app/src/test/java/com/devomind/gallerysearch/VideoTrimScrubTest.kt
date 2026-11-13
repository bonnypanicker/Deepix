package com.devomind.gallerysearch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contracts for scrubbing the trim playhead. The marker used to be display-only: touching it
 * fell through to `Drag.NONE`, so the line could never be moved, and no callback existed to seek the
 * preview. These pin the hit-test, the seek hand-off and the paused-while-dragging contract that
 * stops the 100ms ticker from fighting the finger.
 */
class VideoTrimScrubTest {

    @Test
    fun theTrimViewCanGrabAndMoveThePlayhead() {
        val view = source("VideoTrimView.kt")
        assertTrue(
            "The playhead needs a drag mode of its own",
            "Drag { NONE, START, END, PLAYHEAD }" in view
        )
        assertTrue(
            "Touching the line must select the playhead drag",
            "-> Drag.PLAYHEAD" in view
        )
        assertTrue(
            "Every move must report the new position",
            "onPlayheadScrubbed?.invoke(playheadFraction)" in view
        )
        assertTrue(
            "Scrubbing must stay inside the kept range",
            "fraction.coerceIn(startFraction, endFraction)" in view
        )
    }

    @Test
    fun theActivitySeeksWithThePlayhead() {
        val activity = source("VideoEditorActivity.kt")
        assertTrue(
            "A scrubbed fraction must move the preview",
            "binding.trimView.onPlayheadScrubbed = { fraction ->" in activity
        )
        assertTrue(
            "The preview must follow the marker",
            "player?.seekTo((fraction * durationMs).toLong()" in activity
        )
        assertTrue(
            "The ticker must not overwrite the marker mid-drag",
            "durationMs > 0 && !scrubbingPlayhead" in activity
        )
        assertTrue(
            "A drag that started in playback must resume when released",
            "resumeAfterPlayheadScrub" in activity
        )
    }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/VideoTrimView.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
