package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every bottom bar here sits on the navigation bar, takes the inset as bottom padding, and holds a
 * row of icons measured in dp. A bar that is *itself* a fixed height therefore has the inset taken
 * out of its content: on three-button navigation the inset is ~48dp, so a 64dp bar is left with
 * ~16dp for 28dp icons and the icons render crushed — the Android 17 report this pins down.
 *
 * The house pattern (already in the selection command bar and both editors) is a wrap_content shell
 * around a fixed-height row, so the inset becomes space *below* the row.
 */
class BarInsetLayoutTest {

    private data class Bar(
        val layout: String,
        val source: String,
        val id: String,
        /** The fixed-height content rows the shell wraps, top to bottom. */
        val rows: List<String>
    )

    private val bars = listOf(
        Bar("activity_main.xml", "MainActivity.kt", "bottomPanel", listOf("bottomTabs")),
        Bar("activity_viewer.xml", "ViewerActivity.kt", "bottomControls", listOf("bottomActionRow")),
        Bar("activity_video_editor.xml", "VideoEditorActivity.kt", "bottomArea", listOf("toolBar", "toolActionBar")),
        Bar("activity_photo_editor.xml", "PhotoEditorActivity.kt", "bottomArea", listOf("toolBar", "toolActionBar")),
    )

    @Test
    fun everyBottomBarIsAWrapperAroundAFixedRow() {
        for (bar in bars) {
            val shell = elementWithId(bar.layout, bar.id)
            assertNotNull("${bar.layout} has no @id/${bar.id}", shell)
            assertEquals(
                "${bar.id} must be sized by its row plus the inset, never by the inset alone",
                "wrap_content",
                shell!!.height()
            )
            val children = shell.childElements()
            assertEquals(
                "${bar.id} must hold exactly its content rows",
                bar.rows,
                children.map { it.id() }
            )
            for (row in children) {
                assertTrue(
                    "${bar.layout}: row ${row.id()} must keep a fixed dp height, but was ${row.height()}",
                    Regex("""\d+dp""").matches(row.height())
                )
            }
        }
    }

    @Test
    fun theInsetIsAppliedToTheShellAndNeverToTheRowInsideIt() {
        for (bar in bars) {
            val source = source(bar.source)
            assertTrue(
                "${bar.source} must keep applying the navigation-bar inset to the shell",
                "binding.${bar.id}.updatePadding(bottom = " in source
            )
            for (row in bar.rows) {
                assertFalse(
                    "${bar.source} must not push the inset into ${row}, the row the icons live in",
                    "binding.$row.updatePadding(" in source
                )
            }
        }
    }

    @Test
    fun theShellsContentsAreNeverReplacedOnlyTheRowIsRepopulated() {
        // MainActivity rebuilds the tab strip from BottomBarConfig; clearing the shell would delete
        // the row and leave the tabs as direct children of a wrap_content vertical container.
        val main = source("MainActivity.kt")
        assertFalse("the shell must keep its row", "binding.bottomPanel.removeAllViews()" in main)
        assertTrue("tabs belong to the row", "binding.bottomTabs.removeAllViews()" in main)
        assertTrue("tabs belong to the row", "binding.bottomTabs.addView(" in main)
        // The cramped-screen height budget sizes the row, not the shell.
        assertFalse(
            "the height budget must not pin the shell to a fixed height",
            "val bar = binding.bottomPanel" in main
        )
    }

    private fun elementWithId(layout: String, id: String): Element? {
        val file = File(layoutsDir(), layout)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val pending = ArrayDeque<Element>()
        pending.add(document.documentElement)
        while (pending.isNotEmpty()) {
            val element = pending.removeLast()
            if (element.getAttribute("android:id") == "@+id/$id") return element
            pending.addAll(element.childElements())
        }
        return null
    }

    private fun Element.id(): String = getAttribute("android:id").removePrefix("@+id/")

    private fun Element.height(): String = getAttribute("android:layout_height")

    private fun Element.childElements(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()

    private fun layoutsDir(): File = File(moduleDir(), "src/main/res/layout")

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/res/layout/activity_main.xml").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
