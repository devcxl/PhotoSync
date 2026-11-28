package cn.devcxl.photosync.editor.vision

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Guards the diagnostics constant against drift: [OnnxRuntimeInfo.VERSION] must match the pinned
 * `onnxruntime-android` version in `gradle/libs.versions.toml` and must never read like a placeholder.
 *
 * The AAR exposes no version field that can be read without creating an `OrtEnvironment`, so the
 * constant is the only way to show the version without initializing the native runtime.
 */
class OnnxRuntimeInfoTest {

    @Test
    fun shouldExposeANonBlankVersion() {
        assertTrue(OnnxRuntimeInfo.VERSION.isNotBlank())
        assertTrue(OnnxRuntimeInfo.VERSION.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
    }

    @Test
    fun shouldMatchThePinnedCatalogVersion() {
        val toml = locateVersionCatalog()
        assumeTrue("libs.versions.toml not reachable from the test working directory", toml != null)
        val pinned = toml!!
            .readLines()
            .firstOrNull { it.trimStart().startsWith("onnxruntime = ") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
        assertEquals(OnnxRuntimeInfo.VERSION, pinned)
    }

    private fun locateVersionCatalog(): File? = listOf(
        File("gradle/libs.versions.toml"),
        File("../gradle/libs.versions.toml"),
        File("../../gradle/libs.versions.toml")
    ).firstOrNull { it.isFile }
}
