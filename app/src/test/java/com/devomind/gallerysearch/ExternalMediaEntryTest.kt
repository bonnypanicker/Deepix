package com.devomind.gallerysearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Open with" and "Edit with" have to be two activities, because a resolver names an entry after the
 * activity that answers it. They were one activity once, and a tap on a thumbnail in another gallery
 * landed in the editor, where Save overwrites the original file. The split is invisible to a compiler
 * and to the unit tests that cover logic, so it is guarded here over the manifest text — the same
 * shape as [ManifestClassTest], since CI runs the host tests and never runs lint.
 */
class ExternalMediaEntryTest {

    private val viewAction = "android.intent.action.VIEW"
    private val editAction = "android.intent.action.EDIT"

    @Test
    fun theEditDispatcherAnswersEditAndNeverView() {
        val block = activityBlock(".EditDispatchActivity")
        assertTrue("the edit dispatcher should answer $editAction", editAction in block)
        assertFalse(
            "the edit dispatcher answers $viewAction again: a view request would open the editor, " +
                "whose Save overwrites the original",
            viewAction in block
        )
    }

    @Test
    fun theOpenDispatcherAnswersViewForPhotos() {
        val block = activityBlock(".OpenDispatchActivity")
        assertTrue("the open dispatcher should answer $viewAction", viewAction in block)
        assertTrue(
            "a resolver entry needs the DEFAULT category to be offered at all",
            "android.intent.category.DEFAULT" in block
        )
        assertTrue("the open dispatcher should offer photos", "image/*" in block)
        assertTrue("the resolver entry needs its own label", "@string/open_with_pixa" in block)
    }

    @Test
    fun thePickEntryOpensTheAppForTheActionsThatAskForAPhoto() {
        val block = activityBlock(".PickActivity")
        listOf(
            "android.intent.action.GET_CONTENT",
            "android.intent.action.PICK",
            "android.intent.action.OPEN_DOCUMENT"
        ).forEach { action ->
            assertTrue("the pick entry should answer $action", action in block)
        }
        assertTrue(
            "a resolver entry needs the DEFAULT category to be offered at all",
            "android.intent.category.DEFAULT" in block
        )
        assertTrue("the pick entry should offer photos", "image/*" in block)
    }

    /**
     * The library used to be published as a DOCUMENTS_PROVIDER root. A root is enumerated in the
     * system picker's drawer and browsed in place, so a tap on the app's name showed its photos
     * inside another app's file dialog instead of opening the app — which is what the pick entry
     * above is for. Nothing but this test would notice the root coming back.
     */
    @Test
    fun theLibraryIsNotOfferedAsASourceInsideTheSystemPicker() {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml").readText()
        assertFalse(
            "the library is a DOCUMENTS_PROVIDER root again: the system picker browses it there " +
                "instead of opening the app",
            "android.content.action.DOCUMENTS_PROVIDER" in manifest
        )
    }

    /**
     * The activity element that declares [name], up to its own close. A comment written above the
     * *next* activity belongs to that entry's author, not this one, so it must stay out of the block
     * — and a self-closing element ends at its own `/>`, not at the first `/>` inside it.
     */
    private fun activityBlock(name: String): String {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml").readText()
        val declaration = manifest.indexOf("""android:name="$name"""")
        if (declaration < 0) throw AssertionError("$name is not declared in src/main/AndroidManifest.xml")

        val open = manifest.lastIndexOf("<activity", declaration)
        val openingTagEnd = manifest.indexOf('>', declaration) + 1
        val selfClosed = manifest[openingTagEnd - 2] == '/'
        val end = if (selfClosed) {
            openingTagEnd
        } else {
            manifest.indexOf("</activity>", openingTagEnd) + "</activity>".length
        }
        return manifest.substring(open, end)
    }

    /** Gradle starts a test worker in the module directory; the IDE sometimes uses the repository root. */
    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: throw AssertionError("could not find the app module starting from $from")
    }
}
