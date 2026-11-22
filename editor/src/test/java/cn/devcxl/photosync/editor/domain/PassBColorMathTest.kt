package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PassBColorMath] — Pass B2 display-color pipeline:
 * Vibrance (high-chroma clamp + shadow suppression + 45° skin protection),
 * 8-band OKLCH HSL with low-chroma gate `smoothstep(0.01, 0.05, C)`,
 * Cartesian OKLab split toning on C=0 grays, edge defringe, and film fade.
 */
class PassBColorMathTest {

    @Test
    fun shouldStayBitIdentical_whenAllPassB2ParametersAreNeutral() {
        val graph = EditGraph()
        val toneParams = ToneShaderParams.from(graph)
        val colorParams = ColorGradingParams.from(graph)
        val linear = floatArrayOf(0.12f, 0.34f, 0.56f)
        val passBOut = FloatArray(3)
        val toneOnlyOut = FloatArray(3)

        PassBColorMath.apply(toneParams, colorParams, null, linear, passBOut)
        ToneShaderMath.apply(toneParams, linear, toneOnlyOut)

        for (c in 0..2) {
            assertEquals(toneOnlyOut[c], passBOut[c], 1e-6f)
        }
    }

    @Test
    fun shouldProtectSkinHueVividColorsAndDeepShadows_whenVibranceIsBoosted() {
        val vibGraph = EditGraph(color = ColorOps(vibrance = 100))
        val colorParams = ColorGradingParams.from(vibGraph)

        // Muted blue vs 45° skin-hue swatch at identical OKLCH L and C
        val mutedBlue = oklchToSrgb(l = 0.65f, c = 0.08f, hueDeg = 250.0f)
        val skinPatch = oklchToSrgb(l = 0.65f, c = 0.08f, hueDeg = 45.0f)
        val vividCyan = oklchToSrgb(l = 0.75f, c = 0.30f, hueDeg = 200.0f)
        val darkShadow = oklchToSrgb(l = 0.04f, c = 0.06f, hueDeg = 250.0f)

        val blueGain = chromaOf(applyB2(colorParams, mutedBlue)) / chromaOf(mutedBlue)
        val skinGain = chromaOf(applyB2(colorParams, skinPatch)) / chromaOf(skinPatch)
        val vividGain = chromaOf(applyB2(colorParams, vividCyan)) / chromaOf(vividCyan)
        val shadowGain = chromaOf(applyB2(colorParams, darkShadow)) / chromaOf(darkShadow)

        assertTrue("muted blue must get strong vibrance boost: $blueGain", blueGain > 1.4f)
        assertTrue(
            "45° skin hue must gain much less than blue: skin=$skinGain blue=$blueGain",
            skinGain < blueGain - 0.25f
        )
        assertTrue("high chroma near 0.32 must be protected: vivid=$vividGain blue=$blueGain", vividGain < 1.15f)
        assertTrue("deep shadow L<0.05 must be suppressed: shadow=$shadowGain", shadowGain < 1.02f)
    }

    @Test
    fun shouldLeaveNeutralGrayWallCompletelyUntouched_whenAdjustingBlueLuminanceAndHueInHsl() {
        val graph = EditGraph(
            color = ColorOps(
                hsl = HslOps(
                    blue = HslBandShift(hue = 60, saturation = 50, luminance = -80)
                )
            )
        )
        val colorParams = ColorGradingParams.from(graph)

        val gray = floatArrayOf(0.5f, 0.5f, 0.5f)
        val grayOut = applyB2(colorParams, gray)
        for (c in 0..2) {
            assertEquals("gray wall channel $c must not shift", gray[c], grayOut[c], 1e-5f)
        }

        val nearNeutral = floatArrayOf(0.502f, 0.5f, 0.5f)
        val nearNeutralOut = applyB2(colorParams, nearNeutral)
        assertTrue(
            "near-neutral pixels inside C<0.01 gate must not shift luminance",
            abs(nearNeutralOut[0] - nearNeutral[0]) < 1e-4f
        )

        val skyBlue = floatArrayOf(0.30f, 0.45f, 0.80f)
        val blueDarker = ColorGradingParams.from(
            EditGraph(color = ColorOps(hsl = HslOps(blue = HslBandShift(luminance = -80))))
        )
        val blueOut = applyB2(blueDarker, skyBlue)
        assertTrue(
            "blue luminance=-80 must darken sky blue pixels",
            blueOut[2] < skyBlue[2] - 0.12f && srgbToOklab(blueOut)[0] < srgbToOklab(skyBlue)[0] - 0.12f
        )
    }

    @Test
    fun shouldTintZeroChromaGrayWithExactTargetHueAngle_whenCartesianOklabSplitToningIsApplied() {
        val hiHueDeg = 45
        val shHueDeg = 210
        val hiOnly = ColorGradingParams.from(
            EditGraph(
                color = ColorOps(
                    split = SplitToningOps(hiHue = hiHueDeg, hiSat = 100, shHue = shHueDeg, shSat = 0, balance = 100)
                )
            )
        )
        val shOnly = ColorGradingParams.from(
            EditGraph(
                color = ColorOps(
                    split = SplitToningOps(hiHue = hiHueDeg, hiSat = 0, shHue = shHueDeg, shSat = 100, balance = -100)
                )
            )
        )

        val neutralMid = floatArrayOf(0.5f, 0.5f, 0.5f)
        val hiLab = srgbToOklab(applyB2(hiOnly, neutralMid))
        val shLab = srgbToOklab(applyB2(shOnly, neutralMid))

        val hiChroma = sqrt(hiLab[1] * hiLab[1] + hiLab[2] * hiLab[2])
        val shChroma = sqrt(shLab[1] * shLab[1] + shLab[2] * shLab[2])
        assertTrue("C=0 gray must receive highlight split tint chroma: $hiChroma", hiChroma > 0.05f)
        assertTrue("C=0 gray must receive shadow split tint chroma: $shChroma", shChroma > 0.05f)

        val hiAngleDeg = (atan2(hiLab[2], hiLab[1]) * 180.0 / PI).toFloat()
        val shAngleDeg = ((atan2(shLab[2], shLab[1]) * 180.0 / PI).toFloat() + 360.0f) % 360.0f
        assertEquals(hiHueDeg.toFloat(), hiAngleDeg, 2.0f)
        assertEquals(shHueDeg.toFloat(), shAngleDeg, 2.0f)
    }

    @Test
    fun shouldDesaturateHighGradientPurpleAndGreenEdgesOnly_whenDefringeIsEnabled() {
        val params = ColorGradingParams.from(
            EditGraph(effects = EffectsOps(ca = ChromaticAberrationOps(defringe = 100)))
        )
        val purpleFringe = oklchToSrgb(l = 0.55f, c = 0.18f, hueDeg = 315.0f)
        val greenFringe = oklchToSrgb(l = 0.65f, c = 0.16f, hueDeg = 142.0f)
        val blueEdge = oklchToSrgb(l = 0.50f, c = 0.18f, hueDeg = 264.0f)

        val flatPurpleOut = applyB2(params, purpleFringe, edgeGradient = 0.0f)
        val edgePurpleOut = applyB2(params, purpleFringe, edgeGradient = 0.6f)
        val edgeGreenOut = applyB2(params, greenFringe, edgeGradient = 0.6f)
        val edgeBlueOut = applyB2(params, blueEdge, edgeGradient = 0.6f)

        assertEquals(chromaOf(purpleFringe), chromaOf(flatPurpleOut), 1e-4f)
        assertTrue(chromaOf(edgePurpleOut) < chromaOf(purpleFringe) * 0.35f)
        assertTrue(chromaOf(edgeGreenOut) < chromaOf(greenFringe) * 0.35f)
        assertTrue(chromaOf(edgeBlueOut) > chromaOf(blueEdge) * 0.90f)
    }

    @Test
    fun shouldLiftPureBlackFloorAndSlightlyDesaturate_whenFadeIsApplied() {
        val params = ColorGradingParams.from(EditGraph(color = ColorOps(fade = 100)))
        val blackOut = applyB2(params, floatArrayOf(0.0f, 0.0f, 0.0f))
        for (c in 0..2) {
            assertEquals(0.12f, blackOut[c], 1e-4f)
        }

        val vivid = floatArrayOf(0.9f, 0.2f, 0.2f)
        val vividOut = applyB2(params, vivid)
        assertTrue("fade must lower chroma of vivid red", chromaOf(vividOut) < chromaOf(vivid))
    }

    @Test
    fun shouldDesaturateHighGradientPurpleFringePixelViaNeighborGradient_whenRenderedThroughLinearRawPreview() {
        val purpleSrgb = oklchToSrgb(l = 0.55f, c = 0.18f, hueDeg = 315.0f)
        val pr = ExposureShaderMath.decodeSrgb(purpleSrgb[0])
        val pg = ExposureShaderMath.decodeSrgb(purpleSrgb[1])
        val pb = ExposureShaderMath.decodeSrgb(purpleSrgb[2])
        // 3x3 linear RAW image: black top row, purple fringe center row, white bottom row (high edge gradient)
        val rgba = FloatArray(9 * 4)
        for (x in 0..2) {
            val mid = (3 + x) * 4
            rgba[mid] = pr
            rgba[mid + 1] = pg
            rgba[mid + 2] = pb
            rgba[mid + 3] = 1.0f
            val bot = (6 + x) * 4
            rgba[bot] = 1.0f
            rgba[bot + 1] = 1.0f
            rgba[bot + 2] = 1.0f
            rgba[bot + 3] = 1.0f
        }
        val buffer = cn.devcxl.photosync.wrapper.LinearRawBuffer.fromSamples(
            width = 3,
            height = 3,
            autoBrightGain = 1.0f,
            effectiveIso = 100.0f,
            rgbaSamples = rgba
        )
        val noDefringe = LinearRawPreviewRenderer.renderArgb(buffer, EditGraph(baseLook = BaseLook.LINEAR_NEUTRAL))
        val defringed = LinearRawPreviewRenderer.renderArgb(
            buffer,
            EditGraph(
                baseLook = BaseLook.LINEAR_NEUTRAL,
                effects = EffectsOps(ca = ChromaticAberrationOps(defringe = 100))
            )
        )
        val centerIdx = 4
        assertTrue(chromaOfPixel(defringed[centerIdx]) < chromaOfPixel(noDefringe[centerIdx]) * 0.40f)
    }

    private fun chromaOfPixel(argb: Int): Float = chromaOf(
        floatArrayOf(
            ((argb ushr 16) and 0xFF) / 255.0f,
            ((argb ushr 8) and 0xFF) / 255.0f,
            (argb and 0xFF) / 255.0f
        )
    )

    private fun applyB2(
        params: ColorGradingParams,
        srgb: FloatArray,
        edgeGradient: Float = 0.0f
    ): FloatArray {
        val out = FloatArray(3)
        PassBColorMath.applyDisplayColor(params, null, srgb, out, edgeGradient)
        return out
    }

    private fun oklchToSrgb(l: Float, c: Float, hueDeg: Float): FloatArray {
        val rad = (hueDeg * PI / 180.0).toFloat()
        val lab = FloatArray(3)
        val lin = FloatArray(3)
        OklabColorMath.oklchToOklab(floatArrayOf(l, c, rad), lab)
        OklabColorMath.oklabToLinearSrgb(lab, lin)
        return FloatArray(3) { ExposureShaderMath.encodeSrgb(lin[it].coerceIn(0.0f, 1.0f)) }
    }

    private fun srgbToOklab(srgb: FloatArray): FloatArray {
        val lin = FloatArray(3) { ExposureShaderMath.decodeSrgb(srgb[it]) }
        val lab = FloatArray(3)
        OklabColorMath.linearSrgbToOklab(lin, lab)
        return lab
    }

    private fun chromaOf(srgb: FloatArray): Float {
        val lab = srgbToOklab(srgb)
        return sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    }
}
