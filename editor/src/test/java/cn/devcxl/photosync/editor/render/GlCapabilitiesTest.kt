package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [GlCapabilities] — the probe result and fallback tier of **one** EGL context.
 *
 * The regression guarded here is cross-context contamination: a shader failure or a rejected
 * `GL_RGBA16F` attachment on one context must never change what another context believes about its
 * own driver.
 */
class GlCapabilitiesTest {

    private fun driver(
        renderer: String = "Mali-G78",
        version: String = ES_3_0,
        extensions: String = HALF_FLOAT_EXTENSION,
        maxTextureSize: Int = 8192
    ): GlDriverInfo = GlDriverInfo(
        renderer = renderer,
        version = version,
        extensions = extensions,
        maxTextureSize = maxTextureSize
    )

    @Test
    fun shouldReportTheSafeRgba8Tier_whenTheContextWasNeverProbed() {
        val capabilities = GlCapabilities()

        assertEquals(GlCapabilities.Tier.RGBA8_DITHERED_TARGETS, capabilities.tier)
        assertFalse(capabilities.supportsHalfFloatTargets)
        assertFalse(capabilities.isMinimalFallback)
        assertNull(capabilities.minimalFallbackStage)
    }

    @Test
    fun shouldSelectHalfFloatTier_whenTheDriverAdvertisesHalfFloatColorBuffers() {
        val capabilities = GlCapabilities()

        capabilities.refresh(driver())

        assertEquals(GlCapabilities.Tier.HALF_FLOAT_TARGETS, capabilities.tier)
        assertTrue(capabilities.supportsHalfFloatTargets)
    }

    @Test
    fun shouldSelectHalfFloatTier_whenTheDriverIsEs31WithoutTheExtension() {
        val capabilities = GlCapabilities()

        capabilities.refresh(driver(version = "OpenGL ES 3.1 v1.r20p0", extensions = NO_EXTENSIONS))

        assertEquals(GlCapabilities.Tier.HALF_FLOAT_TARGETS, capabilities.tier)
    }

    @Test
    fun shouldSelectRgba8Tier_whenTheDriverIsEs30WithoutHalfFloatColorBuffers() {
        val capabilities = GlCapabilities()

        capabilities.refresh(driver(extensions = "GL_EXT_debug_marker"))

        assertEquals(GlCapabilities.Tier.RGBA8_DITHERED_TARGETS, capabilities.tier)
        assertFalse(capabilities.supportsHalfFloatTargets)
    }

    @Test
    fun shouldRaiseTier3_whenAShaderFailsOnThatContext() {
        val capabilities = GlCapabilities()
        capabilities.refresh(driver())

        capabilities.markMinimalFallback("Pass D effects shader")

        assertEquals(GlCapabilities.Tier.MINIMAL_FALLBACK, capabilities.tier)
        assertTrue(capabilities.isMinimalFallback)
        assertEquals("Pass D effects shader", capabilities.minimalFallbackStage)
    }

    @Test
    fun shouldKeepTheTargetFormatIndependent_whenOnlyAShaderFailed() {
        val capabilities = GlCapabilities()
        capabilities.refresh(driver())

        capabilities.markMinimalFallback("Pass C SpatialDetail")

        assertTrue(
            "a shader failure must not change the attachment format of the context",
            capabilities.supportsHalfFloatTargets
        )
    }

    @Test
    fun shouldKeepOnlyTheFirstFailingStage_whenASecondShaderAlsoFails() {
        val capabilities = GlCapabilities()
        capabilities.refresh(driver())

        capabilities.markMinimalFallback("Pass A GeometryCa")
        capabilities.markMinimalFallback("Pass D effects shader")

        assertEquals(GlCapabilities.Tier.MINIMAL_FALLBACK, capabilities.tier)
        assertEquals("Pass A GeometryCa", capabilities.minimalFallbackStage)
    }

    @Test
    fun shouldRestoreTheProbedTier_whenTheContextIsRecreated() {
        val capabilities = GlCapabilities()
        capabilities.refresh(driver())
        capabilities.markMinimalFallback("PresentGlProgram")

        capabilities.refresh(driver())

        assertEquals(GlCapabilities.Tier.HALF_FLOAT_TARGETS, capabilities.tier)
        assertFalse(capabilities.isMinimalFallback)
    }

    @Test
    fun shouldForgetEveryFact_whenResetForANewContext() {
        val capabilities = GlCapabilities()
        capabilities.refresh(driver())
        capabilities.markMinimalFallback("Pass D effects shader")

        capabilities.reset()

        assertEquals(GlCapabilities.Tier.RGBA8_DITHERED_TARGETS, capabilities.tier)
        assertFalse(capabilities.supportsHalfFloatTargets)
        assertFalse(capabilities.isMinimalFallback)
    }

    @Test
    fun shouldDowngradeToRgba8Targets_whenTheRgba16fAttachmentIsIncomplete() {
        val capabilities = GlCapabilities()
        capabilities.refresh(driver())

        capabilities.markHalfFloatTargetsUnavailable(GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT)

        assertEquals(GlCapabilities.Tier.RGBA8_DITHERED_TARGETS, capabilities.tier)
        assertFalse(capabilities.supportsHalfFloatTargets)
        assertFalse("an FBO format rejection is not a shader failure", capabilities.isMinimalFallback)
    }

    @Test
    fun shouldNotShareAShaderFailure_whenAnotherContextIsProbed() {
        val preview = GlCapabilities()
        val bake = GlCapabilities()
        preview.refresh(driver())
        bake.refresh(driver())

        bake.markMinimalFallback("Pass D effects shader")

        assertTrue(bake.isMinimalFallback)
        assertFalse(preview.isMinimalFallback)
        assertEquals(GlCapabilities.Tier.HALF_FLOAT_TARGETS, preview.tier)
    }

    @Test
    fun shouldNotShareAnRgba16fRejection_whenAnotherContextAdvertisesHalfFloat() {
        val preview = GlCapabilities()
        val bake = GlCapabilities()
        preview.refresh(driver())
        bake.refresh(driver())

        bake.markHalfFloatTargetsUnavailable(GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT)

        assertFalse(bake.supportsHalfFloatTargets)
        assertTrue(preview.supportsHalfFloatTargets)
        assertEquals(GlCapabilities.Tier.HALF_FLOAT_TARGETS, preview.tier)
    }

    @Test
    fun shouldNotClearTheOthersFallback_whenAHealthyContextIsProbedLater() {
        val preview = GlCapabilities()
        val bake = GlCapabilities()
        preview.refresh(driver())
        preview.markMinimalFallback("Pass C SpatialDetail")

        bake.refresh(driver())

        assertTrue(preview.isMinimalFallback)
        assertEquals(GlCapabilities.Tier.MINIMAL_FALLBACK, preview.tier)
    }

    private companion object {
        private const val ES_3_0 = "OpenGL ES 3.0 v1.r32p1"
        private const val HALF_FLOAT_EXTENSION = "GL_EXT_color_buffer_half_float GL_EXT_debug_marker"
        private const val NO_EXTENSIONS = ""

        /** `GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT`; inlined to avoid the Android frame constants. */
        private const val GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT = 0x8CD6
    }
}
