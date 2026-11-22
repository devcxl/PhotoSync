package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.render.GeometryCpuResampler
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Seam under test: the Pass A → Pass D ordering contract. Grain and vignette must be evaluated on
 * the post-crop frame exactly like `EditorGlRenderer` does when Pass D renders after Pass B.
 */
class PassDEffectsOrderingTest {

    @Test
    fun shouldApplyVignetteToTheCroppedFrame_whenCropAndVignetteAreBothActive() {
        val outSize = 48
        val graph = EditGraph(
            geometry = GeometryOps(
                crop = CropRect(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
            ),
            effects = EffectsOps(
                vignette = VignetteOps(amount = -60, midpoint = 50, feather = 50, roundness = 0)
            )
        )
        val cropped = GeometryCpuResampler.resampleLinear(
            source = uniformLinearSource(size = 96, value = 0.18f),
            inverseMatrix = GeometryMath.buildPassAInverseMatrix(graph.geometry, srcAspect = 1.0f),
            outputWidth = outSize,
            outputHeight = outSize,
            caRedK = 0.0f,
            caBlueK = 0.0f
        )

        val argb = LinearRawPreviewRenderer.renderArgb(cropped, graph)

        val centre = redChannel(argb, outSize * (outSize / 2) + outSize / 2)
        val corner = redChannel(argb, 0)
        assertTrue("The cropped frame centre must stay lighter than its corner", corner < centre)

        val params = PassDEffectsParams.from(graph, aspectRatio = 1.0f)
        val postCropGain = PassDEffectsMath.vignetteGain(0.0f, 0.0f, params)
        val preCropGain = PassDEffectsMath.vignetteGain(0.25f, 0.25f, params)
        assertEquals("The vignette must follow the cropped frame", postCropGain, corner.toFloat() / centre, 0.05f)
        assertTrue("A pre-crop vignette would have left the corner much lighter", preCropGain > postCropGain + 0.2f)
    }

    @Test
    fun shouldKeepGrainDeterministicAcrossTwoRenders_whenTheSeedIsPersisted() {
        val graph = EditGraph(
            effects = EffectsOps(grain = GrainOps(amount = 60, size = 30, roughness = 40, seed = 4242))
        )
        val source = uniformLinearSource(size = 32, value = 0.4f)

        val first = LinearRawPreviewRenderer.renderArgb(source, graph)
        val second = LinearRawPreviewRenderer.renderArgb(source, graph)

        assertTrue(first.contentEquals(second))
        assertTrue("Grain must vary across the frame", first.toSet().size > 1)
    }

    private fun redChannel(argb: IntArray, index: Int): Int = (argb[index] ushr 16) and 0xFF

    private fun uniformLinearSource(size: Int, value: Float): LinearRawBuffer {
        val samples = FloatArray(size * size * 4)
        for (pixel in 0 until size * size) {
            val base = pixel * 4
            samples[base] = value
            samples[base + 1] = value
            samples[base + 2] = value
            samples[base + 3] = 1.0f
        }
        return LinearRawBuffer.fromSamples(
            width = size,
            height = size,
            autoBrightGain = 1.0f,
            effectiveIso = 100f,
            rgbaSamples = samples
        )
    }
}
