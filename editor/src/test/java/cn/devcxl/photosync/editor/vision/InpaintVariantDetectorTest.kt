package cn.devcxl.photosync.editor.vision

import java.io.File
import java.nio.file.Files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [InpaintVariantDetector] — signature classification and the `(length, sha256)`
 * verdict cache.
 *
 * The assertions lock both supported signatures, the `INCOMPATIBLE` rejection and the cache's
 * invalidation rules, so a locally imported legacy generator keeps working while a wrong file fails
 * with the installer's own failure reason.
 */
class InpaintVariantDetectorTest {

    private val tempDir: File = Files.createTempDirectory("migan-variant").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldClassifyTheLegacyGenerator_whenItsInputIsFloatFourChannel512() {
        val detector = InpaintVariantDetector(OnnxSignatureProbe { GENERATOR_INPUTS })
        val file = modelFile("generator.onnx")

        assertEquals(InpaintVariant.GENERATOR_FIXED_512, detector.detect(file, "sha"))
    }

    @Test
    fun shouldClassifyTheUint8Pipeline_whenImageAndMaskAreDynamicUint8() {
        val detector = InpaintVariantDetector(OnnxSignatureProbe { PIPELINE_INPUTS })
        val file = modelFile("pipeline.onnx")

        assertEquals(InpaintVariant.PIPELINE_UINT8, detector.detect(file, "sha"))
    }

    @Test
    fun shouldRejectWithIncompatible_whenTheSignatureMatchesNoVariant() {
        val detector = InpaintVariantDetector(OnnxSignatureProbe { UNSUPPORTED_INPUTS })
        val file = modelFile("wrong.onnx")

        val failure = runCatching { detector.detect(file, "sha") }.exceptionOrNull()

        assertTrue(failure is AiModelInstallException)
        assertEquals(AiModelFailureReason.INCOMPATIBLE, (failure as AiModelInstallException).reason)
    }

    @Test
    fun shouldClassifyNothing_whenTheInputNamesOrTypesDoNotMatchExactly() {
        val detector = InpaintVariantDetector()

        assertNull(detector.classify(emptyList()))
        assertNull(detector.classify(listOf(GENERATOR_INPUTS.single().copy(name = "images"))))
        assertNull(
            detector.classify(
                listOf(GENERATOR_INPUTS.single().copy(shape = listOf(1L, 4L, -1L, -1L)))
            )
        )
        assertNull(
            detector.classify(PIPELINE_INPUTS.map { it.copy(elementType = ModelTensorType.FLOAT32) })
        )
        assertNull(
            detector.classify(PIPELINE_INPUTS.map { it.copy(shape = listOf(1L, 3L, 512L, 512L)) })
        )
    }

    @Test
    fun shouldSkipTheProbe_whenTheCachedLengthAndShaStillMatch() {
        var probes = 0
        val detector = InpaintVariantDetector(OnnxSignatureProbe {
            probes++
            PIPELINE_INPUTS
        })
        val file = modelFile("cached.onnx")
        val sha = "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b"

        assertEquals(InpaintVariant.PIPELINE_UINT8, detector.detect(file, sha))
        assertEquals(InpaintVariant.PIPELINE_UINT8, detector.detect(file, sha))
        assertEquals("the second detection must hit the cache", 1, probes)
    }

    @Test
    fun shouldReprobe_whenTheFileLengthChanges() {
        var probes = 0
        val detector = InpaintVariantDetector(OnnxSignatureProbe {
            probes++
            PIPELINE_INPUTS
        })
        val file = modelFile("changed.onnx")
        val sha = "sha"

        detector.detect(file, sha)
        file.appendBytes(byteArrayOf(0))

        assertEquals(InpaintVariant.PIPELINE_UINT8, detector.detect(file, sha))
        assertEquals(2, probes)
    }

    @Test
    fun shouldReprobe_whenTheExpectedShaChanges() {
        var probes = 0
        val detector = InpaintVariantDetector(OnnxSignatureProbe {
            probes++
            PIPELINE_INPUTS
        })
        val file = modelFile("sha.onnx")

        detector.detect(file, "first")
        detector.detect(file, "second")

        assertEquals(2, probes)
    }

    @Test
    fun shouldCacheIncompatible_whenTheSignatureWasAlreadyRejected() {
        var probes = 0
        val detector = InpaintVariantDetector(OnnxSignatureProbe {
            probes++
            UNSUPPORTED_INPUTS
        })
        val file = modelFile("rejected.onnx")

        repeat(2) {
            val failure = runCatching { detector.detect(file, "sha") }.exceptionOrNull()
            assertTrue(failure is AiModelInstallException)
        }
        assertEquals("a rejected verdict must be cached too", 1, probes)
    }

    @Test
    fun shouldAcceptAnyFile_whenTheCompatibilityHintIsNull() {
        var probes = 0
        val detector = InpaintVariantDetector(OnnxSignatureProbe {
            probes++
            UNSUPPORTED_INPUTS
        })
        val file = modelFile("any.onnx")

        assertTrue(detector.compatibility().accepts(file, null))
        assertEquals(0, probes)
    }

    @Test
    fun shouldRejectAMismatchedHint_whenCompatibilityIsApplied() {
        val detector = InpaintVariantDetector(OnnxSignatureProbe { GENERATOR_INPUTS })
        val file = modelFile("hint.onnx")

        assertTrue(detector.compatibility().accepts(file, InpaintVariant.GENERATOR_FIXED_512))
        assertFalse(detector.compatibility().accepts(file, InpaintVariant.PIPELINE_UINT8))
    }

    private fun modelFile(name: String): File {
        val file = File(tempDir, name)
        file.writeBytes(ByteArray(8) { it.toByte() })
        return file
    }

    private companion object {
        val GENERATOR_INPUTS = listOf(
            ModelInputSignature("input", ModelTensorType.FLOAT32, listOf(1L, 4L, 512L, 512L))
        )

        val PIPELINE_INPUTS = listOf(
            ModelInputSignature("image", ModelTensorType.UINT8, listOf(1L, 3L, -1L, -1L)),
            ModelInputSignature("mask", ModelTensorType.UINT8, listOf(1L, 1L, -1L, -1L))
        )

        val UNSUPPORTED_INPUTS = listOf(
            ModelInputSignature("input", ModelTensorType.FLOAT32, listOf(1L, 3L, 512L, 512L))
        )
    }
}
