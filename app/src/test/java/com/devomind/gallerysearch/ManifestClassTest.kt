package com.devomind.gallerysearch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A manifest entry whose class does not exist stays invisible until something tries to start it: the
 * app declared a `.SafeViewerActivity` for as long as the Safe existed, while the viewer was a pager
 * inside SafeActivity, and no build noticed. Lint's MissingClass check would have flagged it, but CI
 * runs the unit tests and detekt rather than lint, so this is the version of that check that runs.
 *
 * Each manifest may only name classes the variant it feeds actually compiles: the main manifest is
 * built into the release APK, so its components must live in `src/main`; `src/debug` merges into
 * debug variants only, so it may also draw on `src/debug`.
 */
class ManifestClassTest {

    private val appPackage = "com.devomind.gallerysearch"

    /** `[^>]` spans lines on its own, so a component tag spread over several attributes still matches. */
    private val componentTag = Regex("<(activity|service|receiver|provider|application)(?![\\w:.-])[^>]*>")
    private val componentName = Regex("android:name=\"([^\"]+)\"")

    @Test
    fun everyComponentInTheMainManifestHasItsClass() {
        assertComponentsResolve("src/main/AndroidManifest.xml", listOf("src/main"))
    }

    @Test
    fun everyComponentInTheDebugManifestHasItsClass() {
        assertComponentsResolve("src/debug/AndroidManifest.xml", listOf("src/main", "src/debug"))
    }

    private fun assertComponentsResolve(manifestPath: String, sourceSets: List<String>) {
        val module = moduleDir()
        val manifest = File(module, manifestPath)
        assertTrue("no manifest at ${manifest.absolutePath}: this check would pass on nothing", manifest.isFile)

        val declared = componentNames(manifest.readText())
            .filter { it.startsWith(".") || it.startsWith("$appPackage.") }
        assertTrue("$manifestPath yielded no component names — the reader matched nothing", declared.isNotEmpty())

        val missing = declared.filterNot { classFileExists(module, sourceSets, it) }

        assertTrue("$manifestPath declares $missing with no source file beside it", missing.isEmpty())
    }

    /** The `android:name` of every component open-tag — intent actions and categories are children, not tags. */
    private fun componentNames(manifest: String): List<String> =
        componentTag.findAll(manifest).mapNotNull { match ->
            componentName.find(match.value)?.groupValues?.get(1)
        }.toList()

    private fun classFileExists(module: File, sourceSets: List<String>, declaredName: String): Boolean {
        val path = (if (declaredName.startsWith(".")) "$appPackage$declaredName" else declaredName)
            .replace('.', '/')
        return sourceSets.any {
            File(module, "$it/java/$path.kt").isFile || File(module, "$it/java/$path.java").isFile
        }
    }

    /** Gradle starts a test worker in the module directory; the IDE sometimes uses the repository root. */
    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: throw AssertionError("could not find the app module starting from $from")
    }
}
