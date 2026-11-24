package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.export.BakePassChain

/**
 * Seam under test: the ownership of [GlCapabilities] inside [PreviewPassPipeline] and
 * [BakePassChain].
 *
 * Each EGL context gets its own instance: the preview's `GLSurfaceView` context and a headless bake
 * context run concurrently in this app, and a failure recorded by one must stay invisible to the
 * other. Before the split a single process-wide object was the source of that contamination.
 */
class GlCapabilitiesIsolationTest {

    @Test
    fun shouldGiveEachPreviewPipelineItsOwnCapabilityScope() {
        val first = PreviewPassPipeline()
        val second = PreviewPassPipeline()

        assertNotSame(first.capabilities, second.capabilities)

        first.capabilities.refresh(halfFloatDriver())
        first.capabilities.markMinimalFallback("Pass D effects shader")

        assertEquals(GlCapabilities.Tier.MINIMAL_FALLBACK, first.capabilities.tier)
        assertEquals(GlCapabilities.Tier.RGBA8_DITHERED_TARGETS, second.capabilities.tier)
        assertFalse(second.capabilities.isMinimalFallback)
    }

    @Test
    fun shouldNotLetABakeShaderFailureRaiseTier3ForThePreview() {
        val bake = BakePassChain()
        val preview = PreviewPassPipeline()
        bake.capabilities.refresh(halfFloatDriver())
        preview.capabilities.refresh(halfFloatDriver())

        bake.capabilities.markMinimalFallback("Pass C SpatialDetail")

        assertTrue(bake.capabilities.isMinimalFallback)
        assertFalse(preview.capabilities.isMinimalFallback)
        assertEquals(GlCapabilities.Tier.HALF_FLOAT_TARGETS, preview.capabilities.tier)
    }

    @Test
    fun shouldNotLetAPreviewFallbackSurviveIntoTheBakeContext() {
        val preview = PreviewPassPipeline()
        val bake = BakePassChain()
        preview.capabilities.refresh(halfFloatDriver())
        preview.capabilities.markMinimalFallback("Pass A GeometryCa")

        bake.capabilities.refresh(halfFloatDriver())

        assertFalse(bake.capabilities.isMinimalFallback)
        assertEquals(GlCapabilities.Tier.HALF_FLOAT_TARGETS, bake.capabilities.tier)
    }

    @Test
    fun shouldNotLetABakeRgba16fRejectionDisableThePreviewsHalfFloatTargets() {
        val bake = BakePassChain()
        val preview = PreviewPassPipeline()
        bake.capabilities.refresh(halfFloatDriver())
        preview.capabilities.refresh(halfFloatDriver())

        bake.capabilities.markHalfFloatTargetsUnavailable(GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT)

        assertFalse(bake.capabilities.supportsHalfFloatTargets)
        assertTrue(preview.capabilities.supportsHalfFloatTargets)
    }

    @Test
    fun shouldStartEveryFreshPipelineOnTheSafeRgba8Tier() {
        val preview = PreviewPassPipeline()

        assertFalse(
            "an unprobed context must never assume half-float attachments render",
            preview.capabilities.supportsHalfFloatTargets
        )
        assertEquals(GlCapabilities.Tier.RGBA8_DITHERED_TARGETS, preview.capabilities.tier)
    }

    private fun halfFloatDriver(): GlDriverInfo = GlDriverInfo(
        renderer = "Adreno (TM) 740",
        version = "OpenGL ES 3.2 v1.r32p1",
        extensions = "GL_EXT_color_buffer_half_float",
        maxTextureSize = 16384
    )

    private companion object {
        /** `GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT`; inlined to avoid the Android frame constants. */
        private const val GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT = 0x8CD6
    }
}
