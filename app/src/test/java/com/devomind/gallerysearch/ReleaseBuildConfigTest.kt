package com.devomind.gallerysearch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for the release build. Play's bundle analysis reads the shipped DEX: with R8 off it
 * reports the build as unoptimised and essentially unobfuscated, and the bundle carries code the
 * debug build already had. These assertions are the reminder that turning it off again is a release
 * decision, not a local one — and that the keeps reflection needs stay next to it.
 */
class ReleaseBuildConfigTest {

    @Test
    fun releaseIsShrunkOptimisedAndObfuscated() {
        val build = File(moduleDir(), "build.gradle").readText()
        assertTrue(
            "The release build type must turn R8 on; without it Play reports low DEX optimisation",
            build.contains("minifyEnabled true")
        )
        assertTrue("Unused resources should not ship", build.contains("shrinkResources true"))
        assertTrue(
            "Library locale strings must not ship in an English-only app",
            build.contains("localeFilters.add(\"en\")")
        )
        assertTrue(
            "R8 must read the app's own keep rules",
            build.contains("proguardFiles getDefaultProguardFile(\"proguard-android-optimize.txt\"), " +
                "\"proguard-rules.pro\"")
        )
    }

    @Test
    fun keepsCoverTheJniNameBindings() {
        val rules = File(moduleDir(), "proguard-rules.pro").readText()
        // libonnxruntime4j_jni.so exports Java_ai_onnxruntime_* symbols, so renaming those classes
        // turns the first inference into an UnsatisfiedLinkError that no JVM test can catch.
        assertTrue(
            "ONNX Runtime binds its Java API by class name from native code",
            rules.contains("-keep class ai.onnxruntime.** { *; }")
        )
        assertTrue(
            "Glide loads the generated module by name",
            rules.contains("com.bumptech.glide.GeneratedAppGlideModuleImpl")
        )
    }

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/PhotoEditorActivity.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
