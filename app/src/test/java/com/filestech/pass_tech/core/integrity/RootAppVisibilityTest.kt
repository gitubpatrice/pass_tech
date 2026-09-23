package com.filestech.pass_tech.core.integrity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Whether the root managers the code looks for are ones this app is allowed to see.
 *
 * From Android 11 an app sees no package it has not named, and `targetSdk` is well past that. So
 * `getPackageManager().getPackageInfo(...)` answered "not installed" for all five of
 * [IntegritySignals.ROOT_APPS] on every modern phone, whether they were there or not: half the root
 * check was blind, and nothing said so. `IntegritySignalsTest` cannot see that — it hands the rule a
 * fake predicate, which is the point of that test and the reason this one has to exist beside it.
 *
 * This is the pair of places that must agree. Adding a sixth name to [IntegritySignals.ROOT_APPS]
 * without adding it to the manifest would put the hole straight back, and only this test would say so.
 */
class RootAppVisibilityTest {

    @Test
    fun `every root manager the code looks for is declared in the manifest`() {
        val manifest = manifest().readText()
        val declared = DECLARED.findAll(manifest).map { it.groupValues[1] }.toSet()
        assertThat(declared).containsAtLeastElementsIn(IntegritySignals.ROOT_APPS)
    }

    /**
     * Named one by one, never the blanket permission: a store review has no reason to object to five
     * names. The comment beside the declaration says so in words, so what is checked here is a
     * DECLARATION and not the string — the first version of this test failed on its own comment.
     */
    @Test
    fun `the app never asks to see every package on the phone`() {
        val declarations = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest().readText())
        assertThat(declarations.map { it.groupValues[1] }.toList()).doesNotContain("android.permission.QUERY_ALL_PACKAGES")
    }

    /**
     * Run from the module directory by Gradle, from the repository root by some IDEs: both are tried
     * rather than assumed, because a test that silently reads nothing would pass on an empty string.
     */
    private fun manifest(): File {
        val candidates = listOf(File(MANIFEST), File("app/$MANIFEST"))
        return candidates.firstOrNull { it.isFile } ?: error("manifest not found, tried: $candidates")
    }

    private companion object {
        const val MANIFEST = "src/main/AndroidManifest.xml"
        val DECLARED = Regex("""<package\s+android:name="([^"]+)"\s*/>""")
    }
}
