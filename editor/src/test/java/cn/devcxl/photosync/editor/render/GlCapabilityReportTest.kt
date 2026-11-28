package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Seam under test: [GlCapabilityRegistry] — the read-only diagnostics source. A probe must publish the
 * driver facts, and a Tier 3 shader failure must be visible without creating another GL context.
 */
class GlCapabilityReportTest {

    @Test
    fun shouldPublishDriverFacts_whenAContextIsProbed() {
        val capabilities = GlCapabilities()
        capabilities.refresh(
            GlDriverInfo(
                renderer = "Adreno 640",
                version = "OpenGL ES 3.2",
                extensions = "GL_EXT_color_buffer_half_float",
                maxTextureSize = 16_384
            )
        )

        val report = GlCapabilityRegistry.lastReport

        assertEquals("Adreno 640", report?.renderer)
        assertEquals("OpenGL ES 3.2", report?.version)
        assertEquals(16_384, report?.maxTextureSize)
        assertEquals(true, report?.supportsHalfFloatTargets)
        assertNull(report?.minimalFallbackStage)
    }

    @Test
    fun shouldRecordTheTier3Stage_whenAShaderFails() {
        val capabilities = GlCapabilities()
        capabilities.refresh(GlDriverInfo("tier2", "OpenGL ES 3.0", "", 4096))
        capabilities.markMinimalFallback("Pass D effects shader")

        val report = GlCapabilityRegistry.lastReport

        assertEquals(false, report?.supportsHalfFloatTargets)
        assertEquals("Pass D effects shader", report?.minimalFallbackStage)
    }
}
