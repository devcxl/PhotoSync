package cn.devcxl.photosync.editor.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [AiModelCatalog] — the pinned artifact manifest.
 *
 * The size and SHA-256 of every entry are the integrity contract shared by the multi-mirror
 * downloader and the SAF import path, so a wrong value is a build-level regression rather than a
 * runtime surprise on a user's device.
 */
class AiModelCatalogTest {

    @Test
    fun shouldPinEveryListedModel_whenTheCatalogIsShipped() {
        val specs = AiModelCatalog.onDemandModels

        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        assertEquals(specs.size, specs.map { it.fileName }.toSet().size)
        for (spec in specs) {
            assertTrue("${spec.id} must be pinnable to be installable", spec.isPinned)
            assertTrue("${spec.id} sha256 must be 64 lower-case hex", spec.hasPinnedSha256())
            assertTrue("${spec.id} version must not be a placeholder", spec.version != "unreleased")
            assertTrue("${spec.id} must point at an .onnx file", spec.fileName.endsWith(".onnx"))
            assertFalseMirrorsSpeakPlainHttp(spec)
        }
    }

    @Test
    fun shouldPinTheVerifiedMiganGeneratorExport_whenTheArtifactIsReleased() {
        val spec = AiModelCatalog.MI_GAN_GENERATOR

        assertEquals("migan-generator-512", spec.id)
        assertEquals("1", spec.version)
        assertEquals("migan_generator_512_v1.onnx", spec.fileName)
        assertEquals(28_026_303L, spec.byteSize)
        assertEquals(
            "522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe",
            spec.sha256
        )
        assertEquals(
            listOf(
                "https://github.com/devcxl/PhotoSync/releases/download/ai-models-v1/" +
                    "migan_generator_512.onnx"
            ),
            spec.mirrorUrls
        )
        assertEquals("MIT", spec.license.name)
        assertEquals(
            "https://github.com/Picsart-AI-Research/MI-GAN/blob/main/LICENSE-WEIGHTS",
            spec.license.url
        )
    }

    private fun AiModelSpec.hasPinnedSha256(): Boolean =
        sha256.matches(Regex("^[0-9a-f]{64}$"))

    private fun assertFalseMirrorsSpeakPlainHttp(spec: AiModelSpec) {
        for (url in spec.mirrorUrls) {
            assertTrue("${spec.id} mirror must be https: $url", url.startsWith("https://"))
        }
    }
}
