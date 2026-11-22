package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PassDEffectsMath] — the CPU reference of Pass D. Every assertion is a pixel or
 * scalar outcome the GLSL shader must reproduce.
 */
class PassDEffectsMathTest {

    @Test
    fun shouldInterpolateLinearly_whenSamplingAnIdentityRampTetrahedrally() {
        val lut = identityRamp(size = 5)
        val sample = PassDEffectsMath.sampleTetrahedral(lut, 5, 0.31f, 0.62f, 0.87f)

        assertEquals(0.31f, sample[0], 1e-4f)
        assertEquals(0.62f, sample[1], 1e-4f)
        assertEquals(0.87f, sample[2], 1e-4f)
    }

    @Test
    fun shouldReadTheLastTexel_whenTheSampleSitsOnTheUnitCorner() {
        val last = floatArrayOf(0.9f, 0.4f, 0.15f)
        val lut = FloatArray(2 * 2 * 2 * 3)
        for (index in 0 until 8) {
            val base = index * 3
            lut[base] = if (index == 7) last[0] else 0.0f
            lut[base + 1] = if (index == 7) last[1] else 0.0f
            lut[base + 2] = if (index == 7) last[2] else 0.0f
        }

        val sample = PassDEffectsMath.sampleTetrahedral(lut, 2, 1.0f, 1.0f, 1.0f)

        assertEquals(last[0], sample[0], 1e-6f)
        assertEquals(last[1], sample[1], 1e-6f)
        assertEquals(last[2], sample[2], 1e-6f)
    }

    @Test
    fun shouldClampTheBaseGridIndex_whenOnlyRedSitsOnTheLastSlice() {
        val lut = FloatArray(3 * 3 * 3 * 3) { if (it % 3 == 0) 1.0f else 0.0f }
        val base = ((2 * 3 + 1) * 3 + 2) * 3
        lut[base] = 0.25f
        lut[base + 1] = 0.5f
        lut[base + 2] = 0.75f

        val sample = PassDEffectsMath.sampleTetrahedral(lut, 3, 1.0f, 0.5f, 1.0f)

        assertEquals(0.25f, sample[0], 1e-6f)
        assertEquals(0.5f, sample[1], 1e-6f)
        assertEquals(0.75f, sample[2], 1e-6f)
    }

    @Test
    fun shouldBlendByStrength_whenApplyingA3dLutToDisplayPixels() {
        val lut = Lut3dSamples(2, FloatArray(2 * 2 * 2 * 4) { if (it % 4 == 3) 1.0f else 0.0f })
        val pixels = IntArray(4) { GRAY_PIXEL }
        val params = PassDEffectsParams(lutStrength = 0.5f, lutSize = 2, hasLut = true)

        PassDEffectsMath.applyDisplayEffects(pixels, 2, 2, params, lut)

        val expected = (GRAY_LEVEL * 0.5f * 255.0f + 0.5f).toInt()
        assertTrue(abs(expected - ((pixels[0] ushr 16) and 0xFF)) <= 1)
    }

    @Test
    fun shouldReturnTheSameGrain_whenTheSeedIsRepeated() {
        val params = grainParams(seed = 4242)

        val first = PassDEffectsMath.grainOffset(0.31f, 0.77f, params, 0.5f)
        val repeated = PassDEffectsMath.grainOffset(0.31f, 0.77f, params, 0.5f)
        val otherSeed = PassDEffectsMath.grainOffset(0.31f, 0.77f, grainParams(seed = 777), 0.5f)

        assertEquals(first, repeated, 0.0f)
        assertNotEquals(first, otherSeed)
    }

    @Test
    fun shouldFadeGrainOutOfBlackAndWhite_whenApplyingTheMidtoneEnvelope() {
        assertEquals(1.0f, PassDEffectsMath.grainEnvelope(0.5f), 1e-4f)
        assertEquals(0.0f, PassDEffectsMath.grainEnvelope(0.0f), 1e-6f)
        assertEquals(0.0f, PassDEffectsMath.grainEnvelope(1.0f), 1e-6f)
        assertTrue(PassDEffectsMath.grainEnvelope(0.1f) < 0.7f)

        val params = grainParams(seed = 9)
        val shadowGrain = abs(PassDEffectsMath.grainOffset(0.4f, 0.4f, params, 0.0f))
        assertEquals(0.0f, shadowGrain, 1e-6f)
    }

    @Test
    fun shouldTurnVignetteOff_whenTheAmountIsZero() {
        assertEquals(1.0f, PassDEffectsMath.vignetteGain(0.0f, 0.0f, PassDEffectsParams()), 0.0f)
        assertEquals(1.0f, PassDEffectsMath.vignetteGain(0.5f, 0.5f, PassDEffectsParams()), 0.0f)
    }

    @Test
    fun shouldDarkenCornersAndKeepCentre_whenTheVignetteAmountIsNegative() {
        val params = vignetteParams(amount = -60, midpoint = 50, feather = 50, roundness = 0)

        val corner = PassDEffectsMath.vignetteGain(0.0f, 0.0f, params)
        val centre = PassDEffectsMath.vignetteGain(0.5f, 0.5f, params)

        assertEquals(1.0f - 0.6f, corner, 1e-4f)
        assertEquals(1.0f, centre, 1e-4f)
    }

    @Test
    fun shouldTransitionSmoothly_whenCrossingTheFeatherBand() {
        val params = vignetteParams(amount = -100, midpoint = 50, feather = 50, roundness = 0)

        // The diagonal has radius 2 * (u - 0.5), so these points sit on 0, 0.25, 0.5 and 0.75.
        assertEquals(0.0f, PassDEffectsMath.vignetteRadius(0.5f, 0.5f, params), 1e-4f)
        assertEquals(0.25f, PassDEffectsMath.vignetteRadius(0.625f, 0.625f, params), 1e-4f)
        assertEquals(0.5f, PassDEffectsMath.vignetteRadius(0.75f, 0.75f, params), 1e-4f)

        assertEquals(1.0f, PassDEffectsMath.vignetteGain(0.625f, 0.625f, params), 1e-4f)
        assertEquals(0.5f, PassDEffectsMath.vignetteGain(0.75f, 0.75f, params), 1e-3f)
        assertEquals(0.0f, PassDEffectsMath.vignetteGain(0.875f, 0.875f, params), 1e-3f)
    }

    @Test
    fun shouldReachEveryCornerOnce_whenRoundnessSweepsFromSquareToCircle() {
        val square = vignetteParams(amount = -100, midpoint = 50, feather = 50, roundness = -100)
        val circle = vignetteParams(amount = -100, midpoint = 50, feather = 50, roundness = 100)

        assertEquals(1.0f, PassDEffectsMath.vignetteRadius(0.0f, 0.0f, square), 1e-4f)
        assertEquals(1.0f, PassDEffectsMath.vignetteRadius(0.0f, 0.0f, circle), 1e-4f)
        assertTrue(
            PassDEffectsMath.vignetteRadius(0.0f, 0.5f, square) >
                PassDEffectsMath.vignetteRadius(0.0f, 0.5f, circle)
        )
    }

    @Test
    fun shouldStayWithinOneEightBitStep_whenDithering() {
        for (x in 0 until 32) {
            val dither = PassDEffectsMath.triangularDither(x.toFloat(), 1.0f, seed = 104729)
            assertTrue(abs(dither) <= PassDEffectsMath.DITHER_AMPLITUDE)
        }
        assertEquals(1.0f / 255.0f, PassDEffectsMath.DITHER_AMPLITUDE, 1e-9f)
    }

    @Test
    fun shouldLeavePixelsUntouched_whenEveryEffectIsNeutral() {
        val pixels = IntArray(9) { GRAY_PIXEL }

        val result = PassDEffectsMath.applyDisplayEffects(pixels, 3, 3, PassDEffectsParams(), null)

        assertTrue(result.contentEquals(IntArray(9) { GRAY_PIXEL }))
    }

    @Test
    fun shouldMapSlidersOntoPassDUniforms_whenBuildingParamsFromTheGraph() {
        val graph = EditGraph(
            color = ColorOps(lut = LutRef(id = "kodak_2383", strength = 60)),
            effects = EffectsOps(
                grain = GrainOps(amount = 40, size = 30, roughness = 70, seed = 4242),
                vignette = VignetteOps(amount = -30, midpoint = 60, feather = 40, roundness = -20)
            )
        )

        val params = PassDEffectsParams.from(graph, aspectRatio = 1.5f)

        assertEquals(0.6f, params.lutStrength, 1e-6f)
        assertEquals(Lut16fCache.GRID_SIZE, params.lutSize)
        assertTrue(params.hasLutEffects)
        assertEquals(0.4f, params.grainAmount, 1e-6f)
        assertEquals(0.3f, params.grainSize, 1e-6f)
        assertEquals(0.7f, params.grainRoughness, 1e-6f)
        assertEquals(4242, params.grainSeed)
        assertEquals(-0.3f, params.vignetteAmount, 1e-6f)
        assertEquals(0.6f, params.vignetteMidpoint, 1e-6f)
        assertEquals(0.4f, params.vignetteFeather, 1e-6f)
        assertEquals(-0.2f, params.vignetteRoundness, 1e-6f)
        assertEquals(1.5f, params.aspectRatio, 1e-6f)
        assertTrue(!params.isIdentity)
    }

    private fun identityRamp(size: Int): FloatArray {
        val samples = FloatArray(size * size * size * 3)
        val steps = (size - 1).toFloat()
        for (blue in 0 until size) {
            for (green in 0 until size) {
                for (red in 0 until size) {
                    val base = ((blue * size + green) * size + red) * 3
                    samples[base] = red / steps
                    samples[base + 1] = green / steps
                    samples[base + 2] = blue / steps
                }
            }
        }
        return samples
    }

    private fun grainParams(seed: Int): PassDEffectsParams = PassDEffectsParams(
        grainAmount = 1.0f,
        grainSize = 0.25f,
        grainRoughness = 0.5f,
        grainSeed = seed
    )

    private fun vignetteParams(
        amount: Int,
        midpoint: Int,
        feather: Int,
        roundness: Int
    ): PassDEffectsParams = PassDEffectsParams.from(
        graph = EditGraph(
            effects = EffectsOps(
                vignette = VignetteOps(
                    amount = amount,
                    midpoint = midpoint,
                    feather = feather,
                    roundness = roundness
                )
            )
        ),
        aspectRatio = 1.0f
    )

    private companion object {
        private const val GRAY_LEVEL = 0.5f
        private val GRAY_PIXEL = -0x808080
    }
}
