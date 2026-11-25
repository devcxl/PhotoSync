package cn.devcxl.photosync.editor.vision

import java.io.File
import java.nio.file.Files

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.AiAssetStore

/**
 * Seam under test: [ModnetSubjectSegmenter] — the MODNet NCHW preprocessing contract, the
 * `[1,1,512,512]` output decoding and the content-addressed mask cache.
 *
 * The native runtime is replaced by a fake [OrtSessionHandle] and a capturing [ModnetInference], so
 * no `.so` is loaded and the test can inspect the exact tensor values the model would receive.
 */
class ModnetSubjectSegmenterTest {

    private val tempDir: File = Files.createTempDirectory("modnet-segmenter").toFile()
    private val modelFile: File = File(tempDir, "modnet.onnx").apply { writeBytes(byteArrayOf(1)) }
    private val assets = AiAssetStore(tempDir)

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldFeedNchwNormalizedTensor_whenSegmentingAnImage() {
        val captured = CapturingInference()
        val segmenter = segmenter(captured)
        val image = twoByTwoImage()

        val result = segmenter.segment(image, modelFile)

        assertTrue(result.isSuccess)
        val input = captured.lastInput
        assertEquals(3 * 512 * 512, input.size)
        // NCHW layout with MODNet's (x - 0.5) / 0.5 normalization.
        assertEquals(1.0f, input[0], 0.01f)
        assertEquals(-1.0f, input[512 * 512], 0.01f)
        assertEquals(-1.0f, input[2 * 512 * 512], 0.01f)
        // The bottom-right pixel is white in every channel.
        val last = 512 * 512 - 1
        assertEquals(1.0f, input[last], 0.01f)
        assertEquals(1.0f, input[512 * 512 + last], 0.01f)
        assertEquals(1.0f, input[2 * 512 * 512 + last], 0.01f)
    }

    @Test
    fun shouldClampTheProbabilityMap_whenTheModelOutputOvershoots() {
        val segmenter = segmenter(CapturingInference(overshoot = true))
        val image = twoByTwoImage()

        val mask = segmenter.segment(image, modelFile).getOrThrow()

        assertEquals(512, mask.width)
        assertEquals(512, mask.height)
        assertTrue(mask.alpha.all { it in 0.0f..1.0f })
    }

    @Test
    fun shouldReuseTheContentAddressedMask_whenReopeningTheStore() {
        val segmenter = segmenter(CapturingInference(gradient = true))
        val mask = segmenter.segment(twoByTwoImage(), modelFile).getOrThrow()

        val reference = segmenter.saveMask(42L, mask)
        assertNotNull(reference)
        assertTrue(reference!!.relativePath.startsWith("edits/42/subject_mask_"))
        val reopened = ModnetSubjectSegmenter(
            sessions = sessions(),
            assets = AiAssetStore(tempDir),
            inference = CapturingInference()
        )

        val loaded = reopened.loadMask(reference.relativePath)
        assertNotNull(loaded)

        assertArrayEquals(mask.alpha, loaded!!.alpha, 1.0f / 255.0f + 1e-6f)
        assertEquals(64, reference.sha256.length)
    }

    @Test
    fun shouldReturnFailure_whenTheModelFileIsMissing() {
        val result = segmenter(CapturingInference())
            .segment(twoByTwoImage(), File(tempDir, "missing.onnx"))

        assertTrue(result.isFailure)
    }

    private fun segmenter(inference: ModnetInference): ModnetSubjectSegmenter =
        ModnetSubjectSegmenter(sessions(), assets, inference)

    private fun sessions(): OrtSessionManager =
        OrtSessionManager(
            tier = DeviceMemoryTier.LOW,
            sessionFactory = OrtSessionFactory { file -> FakeHandle(file.absolutePath) }
        )

    private fun twoByTwoImage(): SubjectImage = SubjectImage(
        width = 2,
        height = 2,
        argb = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFFFF.toInt()
        )
    )

    private class FakeHandle(override val modelPath: String) : OrtSessionHandle {
        override fun close() = Unit
    }

    private class CapturingInference(
        private val gradient: Boolean = false,
        private val overshoot: Boolean = false
    ) : ModnetInference {

        lateinit var lastInput: FloatArray
            private set

        override fun run(session: OrtSessionHandle, input: FloatArray): FloatArray {
            lastInput = input.copyOf()
            val size = 512 * 512
            return FloatArray(size) { index ->
                when {
                    overshoot -> if (index % 2 == 0) 1.5f else -0.5f
                    gradient -> index.toFloat() / size
                    else -> 1.0f
                }
            }
        }
    }
}
