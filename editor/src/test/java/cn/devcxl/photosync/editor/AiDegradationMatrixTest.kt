package cn.devcxl.photosync.editor

import java.io.File
import java.nio.file.Files
import kotlin.math.abs

import ai.onnxruntime.OrtException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.AiDegradationFixtures.proceduralSkyLayer
import cn.devcxl.photosync.editor.AiDegradationFixtures.refineSkyBand
import cn.devcxl.photosync.editor.AiDegradationFixtures.softEdgeAlpha
import cn.devcxl.photosync.editor.AiDegradationFixtures.subjectMask
import cn.devcxl.photosync.editor.domain.BandGuidedFilter
import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ImageFilters
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.export.BakeSkySupport
import cn.devcxl.photosync.editor.export.BakeSubjectSupport
import cn.devcxl.photosync.editor.render.GlCapabilities
import cn.devcxl.photosync.editor.render.GlDriverInfo
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.OrtSessionFailureReason
import cn.devcxl.photosync.editor.vision.OrtSessionManager
import cn.devcxl.photosync.editor.vision.OrtSessionUnavailableException

/**
 * Cross-cutting rows of the AI safety degradation matrix (spec §7).
 *
 * The individual classes already have focused tests ([OrtSessionManager], `BakeSubjectSupport`,
 * `BakeSkySupport`, `GlCapabilities`). This suite locks the combinations that only appear when the
 * rows are read together: a low-memory device that runs *both* models, a native allocation that
 * fails before a session exists, a guided filter that cannot allocate, the feathered proxy cut-out
 * the bake falls back to, the per-band sky mask continuity that keeps the export seam-free, and the
 * Tier 2/Tier 3 effect gate itself.
 */
class AiDegradationMatrixTest {

    private val tempDir: File = Files.createTempDirectory("ai-degradation").toFile()
    private val modelA: File = File(tempDir, "modnet.onnx").apply { writeBytes(byteArrayOf(1)) }
    private val modelB: File = File(tempDir, "migan.onnx").apply { writeBytes(byteArrayOf(2)) }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shouldReleaseEverySession_whenBothAiModelsRunOnTheLowTier() {
        val factory = RecordingSessionFactory()
        val manager = lowTierManager(factory)

        manager.withSession(modelA) { it }.getOrThrow()
        assertEquals("MODNet must leave no session behind", 0, manager.activeSessionCount)
        manager.withSession(modelB) { it }.getOrThrow()

        assertEquals("MI-GAN must leave no session behind", 0, manager.activeSessionCount)
        assertEquals("one native session per model", 2, factory.created.size)
        assertTrue("every LOW-tier handle must be closed", factory.created.all { it.closed })
        manager.close()
    }

    @Test
    fun shouldReturnOutOfMemoryAndStayEmpty_whenTheSessionFactoryCannotAllocate() {
        val factory = RecordingSessionFactory().apply { failure = OutOfMemoryError("simulated") }
        val manager = lowTierManager(factory)

        val result = manager.withSession(modelA) { it }

        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as OrtSessionUnavailableException
        assertEquals(OrtSessionFailureReason.OUT_OF_MEMORY, failure.reason)
        assertEquals(0, factory.created.size)
        assertEquals(0, manager.activeSessionCount)
        manager.close()
    }

    @Test
    fun shouldReturnRuntimeErrorAndStayEmpty_whenTheModelCannotBeLoaded() {
        val factory = RecordingSessionFactory().apply { failure = OrtException("bad model") }
        val manager = lowTierManager(factory)

        val result = manager.withSession(modelA) { it }

        val failure = result.exceptionOrNull() as OrtSessionUnavailableException
        assertEquals(OrtSessionFailureReason.RUNTIME_ERROR, failure.reason)
        assertEquals(0, manager.activeSessionCount)
        manager.close()
    }

    @Test
    fun shouldFallBackToTheFeatheredAlpha_whenTheGuidedFilterCannotAllocate() {
        val width = 32
        val height = 8
        val alpha = softEdgeAlpha(width, height)
        val guide = FloatArray(width * height) { index ->
            if (index % width < width / 2) 0.8f else 0.2f
        }
        var failures = 1

        val refined = BandGuidedFilter.refine(
            guide = guide,
            alpha = alpha,
            width = width,
            height = height,
            radius = 4,
            allocate = { size ->
                if (failures > 0) {
                    failures--
                    throw OutOfMemoryError("simulated coefficient plane")
                }
                FloatArray(size)
            }
        )
        val expected = BandGuidedFilter.feather(alpha, width, height, radius = 2)

        assertEquals(alpha.size, refined.size)
        assertTrue("the fallback must stay finite", refined.all { it.isFinite() })
        assertTrue("the fallback must stay in [0, 1]", refined.all { it in 0.0f..1.0f })
        for (index in refined.indices) {
            assertEquals("fallback pixel $index", expected[index], refined[index], 1e-6f)
        }
    }

    @Test
    fun shouldResampleTheProxyForeground_whenTheSubjectBandFallsBackFromReadback() {
        val proxyWidth = 16
        val proxyHeight = 8
        val proxyForeground = FloatArray(proxyWidth * proxyHeight * 3) { index ->
            when (index % 3) {
                0 -> 0.8f
                1 -> 0.25f
                else -> 0.1f
            }
        }
        val initial = SubjectComposite(
            width = proxyWidth,
            height = proxyHeight,
            alpha = FloatArray(proxyWidth * proxyHeight) { index ->
                if (index % proxyWidth < proxyWidth / 2) 1.0f else 0.0f
            },
            foreground = proxyForeground,
            background = FloatArray(proxyWidth * proxyHeight * 3) { 0.05f }
        )
        val graph = EditGraph(
            subject = SubjectOps(
                maskRef = "edits/1/subject_mask.png",
                bgMode = SubjectBackgroundMode.TRANSPARENT
            )
        )
        val support = BakeSubjectSupport(graph, subjectMask(64, 16), initial, null, Mat3.identity())

        val composite = checkNotNull(
            support.composeFallbackBand(
                width = 32,
                height = 8,
                band = ColumnBandTiling.fullFrame(32, 8)
            )
        )

        assertTrue("left half must stay opaque", composite.alpha[4 * 32 + 4] > 0.5f)
        assertTrue("right half must stay transparent", composite.alpha[4 * 32 + 28] < 0.5f)
        val expected = ImageFilters.resampleRgb(proxyForeground, proxyWidth, proxyHeight, 32, 8)
        assertEquals(expected[0], composite.foreground[0], 1e-5f)
        assertEquals(expected[31 * 3 + 1], composite.foreground[31 * 3 + 1], 1e-5f)
    }

    @Test
    fun shouldKeepAdjacentSkyBandsContinuous_whenTheMaskIsRefinedPerBand() {
        val cropWidth = 2 * ColumnBandTiling.MAX_BAND_WIDTH
        val cropHeight = 32
        val support = BakeSkySupport(proceduralSkyLayer())
        val strip = ColumnBandTiling.rowStrip(cropHeight, 0)
        val guide = IntArray(cropWidth * cropHeight) { index ->
            val ramp = (index % cropWidth) * 255 / (cropWidth - 1)
            0xFF shl 24 or (ramp shl 16) or (ramp shl 8) or ramp
        }
        val leftBand = ColumnBandTiling.columnBand(cropWidth, cropHeight, strip, 0)
        val rightBand = ColumnBandTiling.columnBand(cropWidth, cropHeight, strip, 1)
        val middleRow = cropHeight / 2

        val left = refineSkyBand(support, guide, leftBand, cropWidth, cropHeight)
        val right = refineSkyBand(support, guide, rightBand, cropWidth, cropHeight)

        val leftEdge = left[middleRow * leftBand.fboWidth + leftBand.halo + leftBand.validWidth - 1]
        val rightEdge = right[middleRow * rightBand.fboWidth + rightBand.halo]
        assertTrue("left band edge must carry sky probability", leftEdge in 0.0f..1.0f)
        assertTrue("right band edge must carry sky probability", rightEdge in 0.0f..1.0f)
        assertTrue(
            "band boundary seam: left=$leftEdge right=$rightEdge",
            abs(leftEdge - rightEdge) < SEAM_TOLERANCE
        )
    }

    @Test
    fun shouldKeepPassDEffectsEnabled_whenTheContextOnlyFellBackToRgba8Targets() {
        val capabilities = GlCapabilities()
        capabilities.refresh(GlDriverInfo("tier2", "OpenGL ES 3.0", "", 4096))

        assertEquals(GlCapabilities.Tier.RGBA8_DITHERED_TARGETS, capabilities.tier)
        assertTrue("Tier 2 only changes the FBO format", capabilities.allowsPassDEffects)
    }

    @Test
    fun shouldDisableEveryPassDEffect_whenTheContextFellBackToTheMinimalShader() {
        val capabilities = GlCapabilities()
        capabilities.refresh(GlDriverInfo("tier2", "OpenGL ES 3.0", "", 4096))

        capabilities.markMinimalFallback("Pass D effects shader")

        assertEquals(GlCapabilities.Tier.MINIMAL_FALLBACK, capabilities.tier)
        assertFalse(capabilities.allowsPassDEffects)
    }

    private fun lowTierManager(factory: RecordingSessionFactory): OrtSessionManager =
        OrtSessionManager(
            tier = DeviceMemoryTier.LOW,
            sessionFactory = factory,
            scheduler = NoopIdleScheduler
        )

    private companion object {
        /** Two adjacent bands must agree within this alpha delta; a seam lands far above it. */
        const val SEAM_TOLERANCE = 0.05f
    }
}
