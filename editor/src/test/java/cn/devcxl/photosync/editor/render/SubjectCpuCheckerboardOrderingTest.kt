package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EffectsOps
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.LutRef
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.VignetteOps
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Seam under test: the CPU preview ordering of the transparent cut-out presentation checkerboard.
 *
 * The GL Pass D shader applies LUT/grain/vignette to the foreground and draws the checkerboard last.
 * The CPU reference renderer must do the same, otherwise cached thumbnails show a darkened
 * checkerboard that the canvas never displays.
 */
class SubjectCpuCheckerboardOrderingTest {

    @Test
    fun shouldKeepTheCheckerboardUntouched_whenLutAndVignetteAreActive() {
        val graph = EditGraph(
            subject = SubjectOps(
                maskRef = "mask.png",
                bgMode = SubjectBackgroundMode.TRANSPARENT
            ),
            color = ColorOps(lut = LutRef(id = "identity", strength = 100)),
            effects = EffectsOps(vignette = VignetteOps(amount = -100, midpoint = 0))
        )
        val composite = SubjectComposite(
            width = 4,
            height = 4,
            alpha = FloatArray(16) { 0.0f },
            foreground = FloatArray(16 * 3) { 1.0f },
            background = FloatArray(16 * 3) { 0.0f }
        )
        val source = LinearRawBuffer.fromSamples(
            width = 4,
            height = 4,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgbaSamples = FloatArray(16 * 4) { 0.18f }
        )

        val pixels = LinearRawPreviewRenderer.renderArgb(
            source = source,
            graph = graph,
            lut = identityLut(),
            beforeEffects = { buffer, width, height ->
                SubjectCpuComposer.applyForeground(
                    pixels = buffer,
                    width = width,
                    height = height,
                    graph = graph,
                    composite = composite,
                    inverseMatrix = Mat3.identity()
                ).copyInto(buffer)
            },
            afterEffects = { buffer, width, height ->
                SubjectCpuComposer.drawCheckerboard(buffer, width, height)
            }
        )

        for (pixel in pixels) {
            assertTrue(
                "Checkerboard pixel was graded by Pass D: ${Integer.toHexString(pixel)}",
                pixel == 0xFF2E2E2E.toInt() || pixel == 0xFF3C3C3C.toInt()
            )
        }
    }

    @Test
    fun shouldPreserveAlpha_whenPassDRunsOnTheTransparentForeground() {
        val graph = EditGraph(
            subject = SubjectOps(
                maskRef = "mask.png",
                bgMode = SubjectBackgroundMode.TRANSPARENT
            ),
            effects = EffectsOps(vignette = VignetteOps(amount = -100))
        )
        val composite = SubjectComposite(
            width = 1,
            height = 1,
            alpha = floatArrayOf(0.0f),
            foreground = floatArrayOf(1.0f, 0.0f, 0.0f),
            background = floatArrayOf(0.0f, 0.0f, 0.0f)
        )

        val foreground = SubjectCpuComposer.applyForeground(
            pixels = IntArray(1) { 0xFF000000.toInt() },
            width = 1,
            height = 1,
            graph = graph,
            composite = composite,
            inverseMatrix = Mat3.identity()
        )

        assertEquals(0, (foreground[0] ushr 24) and 0xFF)
    }

    private fun identityLut(): Lut3dSamples {
        val size = 2
        val rgba = FloatArray(size * size * size * 4)
        var index = 0
        for (blue in 0 until size) {
            for (green in 0 until size) {
                for (red in 0 until size) {
                    rgba[index++] = red.toFloat()
                    rgba[index++] = green.toFloat()
                    rgba[index++] = blue.toFloat()
                    rgba[index++] = 1.0f
                }
            }
        }
        return Lut3dSamples(size, rgba)
    }
}
