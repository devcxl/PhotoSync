package cn.devcxl.photosync.editor.vision

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [AiModelCatalog] — the pinned artifact manifest.
 *
 * The size and SHA-256 of every entry are the integrity contract shared by the multi-mirror
 * downloader and the SAF import path, so a wrong value is a build-level regression rather than a
 * runtime surprise on a user's device. The last test also locks the Kotlin catalog to the manifest
 * embedded in `tools/verify-ai-model-catalog.sh`, which is what CI actually probes against the
 * network.
 */
class AiModelCatalogTest {

    @Test
    fun shouldPinEveryListedModel_whenTheCatalogIsShipped() {
        val specs = AiModelCatalog.onDemandModels

        assertEquals(specs.size, specs.map { it.id }.toSet().size)
        assertEquals(specs.size, specs.map { it.fileName }.toSet().size)
        for (spec in specs) {
            assertTrue("${spec.id} must be downloadable to be installable", spec.isDownloadable)
            assertTrue("${spec.id} must have verifiable bytes", spec.hasPinnedArtifact)
            assertTrue("${spec.id} sha256 must be 64 lower-case hex", spec.hasPinnedSha256())
            assertTrue("${spec.id} version must not be a placeholder", spec.version != "unreleased")
            assertTrue("${spec.id} must point at an .onnx file", spec.fileName.endsWith(".onnx"))
            assertFalseMirrorsSpeakPlainHttp(spec)
        }
    }

    @Test
    fun shouldPinTheVerifiedModnetMirrors_whenTheGithubReleaseIsGone() {
        val spec = AiModelCatalog.MODNET_PORTRAIT

        assertEquals("modnet-portrait-512", spec.id)
        assertEquals("1", spec.version)
        assertEquals("modnet_portrait_512_v1.onnx", spec.fileName)
        assertEquals(25_888_640L, spec.byteSize)
        assertEquals(
            "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9",
            spec.sha256
        )
        assertEquals(
            listOf(
                "https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx",
                "https://hf-mirror.com/Xenova/modnet/resolve/main/onnx/model.onnx"
            ),
            spec.mirrorUrls
        )
        assertEquals("Apache-2.0", spec.license.name)
    }

    @Test
    fun shouldPinTheVerifiedMiganPipeline_whenTheDistributionEntryMoved() {
        val spec = AiModelCatalog.MI_GAN_PIPELINE

        assertEquals("migan-pipeline-512", spec.id)
        assertEquals("1", spec.version)
        assertEquals("migan_pipeline_512_v1.onnx", spec.fileName)
        assertEquals(28_079_181L, spec.byteSize)
        assertEquals(
            "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b",
            spec.sha256
        )
        assertEquals(
            listOf(
                "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx",
                "https://hf-mirror.com/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx",
                "https://huggingface.co/anyisalin/migan-onnx/resolve/main/onnx/model.onnx"
            ),
            spec.mirrorUrls
        )
        assertEquals("MIT", spec.license.name)
        assertEquals(InpaintVariant.PIPELINE_UINT8, spec.variantHint)
        assertTrue("the pipeline entry is the one the settings page downloads", spec.isDownloadable)
    }

    @Test
    fun shouldKeepTheLegacyGeneratorReadOnly_whenItIsOnlyRecognisedForImports() {
        val spec = AiModelCatalog.MI_GAN_GENERATOR_LEGACY

        assertEquals("migan-generator-512", spec.id)
        assertEquals("migan_generator_512_v1.onnx", spec.fileName)
        assertEquals(28_026_303L, spec.byteSize)
        assertEquals(
            "522048b4fc3fae7adff3af48859cd8d19412d02a8a9420614f3d4474a0901bfe",
            spec.sha256
        )
        assertEquals(InpaintVariant.GENERATOR_FIXED_512, spec.variantHint)
        assertTrue("legacy bytes are still verifiable for SAF import", spec.hasPinnedArtifact)
        assertFalse("legacy entry must never appear on a download button", spec.isDownloadable)
        assertTrue("legacy entry is not on the on-demand list", spec !in AiModelCatalog.onDemandModels)
    }

    @Test
    fun shouldAcceptBothMiganPins_whenResolvingALocalImport() {
        val primary = AiModelCatalog.importCandidates(AiModelCatalog.MI_GAN_PIPELINE)
        val legacy = AiModelCatalog.importCandidates(AiModelCatalog.MI_GAN_GENERATOR_LEGACY)

        assertEquals(listOf(AiModelCatalog.MI_GAN_PIPELINE, AiModelCatalog.MI_GAN_GENERATOR_LEGACY), primary)
        assertEquals(listOf(AiModelCatalog.MI_GAN_GENERATOR_LEGACY, AiModelCatalog.MI_GAN_PIPELINE), legacy)
        assertEquals(
            listOf(AiModelCatalog.MODNET_PORTRAIT),
            AiModelCatalog.importCandidates(AiModelCatalog.MODNET_PORTRAIT)
        )
    }

    @Test
    fun shouldMatchTheVerifyScriptManifest_whenTheCatalogChanges() {
        val scriptEntries = verifyScriptEntries()
        val catalogEntries = AiModelCatalog.supportedModels.map { spec ->
            ScriptEntry(
                key = scriptKeyOf(spec),
                id = spec.id,
                fileName = spec.fileName,
                version = spec.version,
                byteSize = spec.byteSize,
                sha256 = spec.sha256,
                urls = spec.mirrorUrls
            )
        }

        assertEquals(
            "tools/verify-ai-model-catalog.sh must list exactly the catalog entries",
            catalogEntries,
            scriptEntries
        )
    }

    private fun scriptKeyOf(spec: AiModelSpec): String = when (spec.id) {
        AiModelCatalog.MODNET_PORTRAIT.id -> "MODNET_PORTRAIT"
        AiModelCatalog.MI_GAN_PIPELINE.id -> "MI_GAN_PIPELINE"
        AiModelCatalog.MI_GAN_GENERATOR_LEGACY.id -> "MI_GAN_GENERATOR_LEGACY"
        else -> error("Unmapped catalog id ${spec.id}")
    }

    private fun verifyScriptEntries(): List<ScriptEntry> {
        val script = File(repoRoot(), "tools/verify-ai-model-catalog.sh")
        assertTrue("verify script must exist at $script", script.isFile)
        val lines = script.readLines()
        val start = lines.indexOfFirst { it.trim() == BEGIN_MARKER }
        val end = lines.indexOfFirst { it.trim() == END_MARKER }
        assertTrue("missing $BEGIN_MARKER", start >= 0)
        assertTrue("missing $END_MARKER", end > start)
        return lines.subList(start + 1, end)
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map(::parseScriptEntry)
    }

    private fun parseScriptEntry(line: String): ScriptEntry {
        val parts = line.split('|')
        assertTrue("manifest line must have at least 6 fields: $line", parts.size >= 6)
        return ScriptEntry(
            key = parts[0],
            id = parts[1],
            fileName = parts[2],
            version = parts[3],
            byteSize = parts[4].toLong(),
            sha256 = parts[5],
            urls = parts.drop(6).filter { it.isNotBlank() }
        )
    }

    private fun repoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(MAX_PARENT_WALK) {
            val candidate = directory ?: error("repository root not found from user.dir")
            if (File(candidate, "tools/verify-ai-model-catalog.sh").isFile) return candidate
            directory = candidate.parentFile
        }
        error("repository root not found from user.dir")
    }

    private fun AiModelSpec.hasPinnedSha256(): Boolean =
        sha256.matches(Regex("^[0-9a-f]{64}$"))

    private fun assertFalseMirrorsSpeakPlainHttp(spec: AiModelSpec) {
        for (url in spec.mirrorUrls) {
            assertTrue("${spec.id} mirror must be https: $url", url.startsWith("https://"))
        }
    }

    private data class ScriptEntry(
        val key: String,
        val id: String,
        val fileName: String,
        val version: String,
        val byteSize: Long,
        val sha256: String,
        val urls: List<String>
    )

    private companion object {
        const val BEGIN_MARKER = "# CATALOG-BEGIN"
        const val END_MARKER = "# CATALOG-END"
        const val MAX_PARENT_WALK = 8
    }
}
