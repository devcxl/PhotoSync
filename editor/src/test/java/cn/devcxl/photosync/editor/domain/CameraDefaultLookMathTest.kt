package cn.devcxl.photosync.editor.domain

import kotlin.math.roundToInt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the GPU-side `camera_default` base look against the browse-page RAW decode
 * implemented in `libraw/src/main/cpp/raw_postprocess.cpp`.
 *
 * The browse-page reference chain below is transcribed directly from that C++ file
 * (16-bit sRGB LUT -> shadow/midtone gamma + smoothstep S-curve -> shadow-faded vibrance),
 * so these assertions compare two implementations rather than recomputing the same formula.
 */
class CameraDefaultLookMathTest {

    @Test
    fun shouldMatchReferenceLiterals_whenEvaluatingShadowMidtoneCurve() {
        assertEquals(0.0f, CameraDefaultLookMath.applyToneCurve(0.0f), 1e-6f)
        assertEquals(1.0f, CameraDefaultLookMath.applyToneCurve(1.0f), 1e-6f)
        assertEquals(0.088197f, CameraDefaultLookMath.applyToneCurve(0.05f), 1e-5f)
        assertEquals(0.251455f, CameraDefaultLookMath.applyToneCurve(0.18f), 1e-5f)
        assertEquals(0.587175f, CameraDefaultLookMath.applyToneCurve(0.5f), 1e-5f)
        assertEquals(0.928450f, CameraDefaultLookMath.applyToneCurve(0.9f), 1e-5f)
    }

    @Test
    fun shouldStayMonotonic_whenEvaluatingShadowMidtoneCurveAcrossTheDomain() {
        var previous = CameraDefaultLookMath.applyToneCurve(0.0f)
        for (step in 1..200) {
            val current = CameraDefaultLookMath.applyToneCurve(step / 200.0f)
            assertTrue("Tone curve must be monotonic at x=$step", current >= previous)
            previous = current
        }
    }

    @Test
    fun shouldLeaveNeutralGrayUnchanged_whenApplyingVibrance() {
        val gray = CameraDefaultLookMath.applyVibrance(0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, gray[0], 1e-6f)
        assertEquals(0.5f, gray[1], 1e-6f)
        assertEquals(0.5f, gray[2], 1e-6f)
    }

    @Test
    fun shouldNotColorizeDeepShadows_whenSampleLumaIsBelowTheFadeFloor() {
        val deepShadow = CameraDefaultLookMath.applyVibrance(10f / 255f, 10f / 255f, 12f / 255f)
        assertEquals(10f / 255f, deepShadow[0], 1e-6f)
        assertEquals(12f / 255f, deepShadow[2], 1e-6f)
    }

    @Test
    fun shouldMatchReferenceVibranceLiterals_whenChromaBoostIsApplied() {
        val muted = CameraDefaultLookMath.applyVibrance(60f / 255f, 30f / 255f, 30f / 255f)
        assertEquals(0.242766f, muted[0], 1e-5f)
        assertEquals(0.115630f, muted[1], 1e-5f)

        val vivid = CameraDefaultLookMath.applyVibrance(200f / 255f, 120f / 255f, 60f / 255f)
        assertEquals(0.805746f, vivid[0], 1e-5f)
        assertEquals(0.466553f, vivid[1], 1e-5f)
        assertEquals(0.212159f, vivid[2], 1e-5f)
    }

    @Test
    fun shouldBoostMutedChromaMoreThanAlreadyVividChroma_whenLumaIsEqual() {
        val vividColor = CameraDefaultLookMath.applyVibrance(0.80f, 0.40f, 0.40f)
        val mutedColor = CameraDefaultLookMath.applyVibrance(0.5778f, 0.46f, 0.46f)
        val vividGain = chroma(vividColor) / chroma(floatArrayOf(0.80f, 0.40f, 0.40f))
        val mutedGain = chroma(mutedColor) / chroma(floatArrayOf(0.5778f, 0.46f, 0.46f))
        assertTrue("Muted chroma must gain more than vivid chroma", mutedGain > vividGain)
    }

    @Test
    fun shouldMatchReferenceLiterals_whenRenderingCameraDefaultAtZeroExposure() {
        val midGray = CameraDefaultLookMath.renderLinearToSrgb(
            linearRed = 0.18f,
            linearGreen = 0.18f,
            linearBlue = 0.18f,
            autoBrightGain = 1.0f,
            exposureEv = 0.0f,
            baseLook = BaseLook.CAMERA_DEFAULT
        )
        assertEquals(0.549743f, midGray[0], 1e-5f)

        val bright = CameraDefaultLookMath.renderLinearToSrgb(
            linearRed = 0.5f,
            linearGreen = 0.5f,
            linearBlue = 0.5f,
            autoBrightGain = 1.0f,
            exposureEv = 0.0f,
            baseLook = BaseLook.CAMERA_DEFAULT
        )
        assertEquals(0.798354f, bright[0], 1e-5f)

        val gained = CameraDefaultLookMath.renderLinearToSrgb(
            linearRed = 0.2f,
            linearGreen = 0.2f,
            linearBlue = 0.2f,
            autoBrightGain = 2.0f,
            exposureEv = 0.0f,
            baseLook = BaseLook.CAMERA_DEFAULT
        )
        assertEquals(0.738431f, gained[0], 1e-5f)
    }

    @Test
    fun shouldEncodeLinearDirectly_whenBaseLookIsLinearNeutral() {
        val neutral = CameraDefaultLookMath.renderLinearToSrgb(
            linearRed = 0.3f,
            linearGreen = 0.3f,
            linearBlue = 0.3f,
            autoBrightGain = 1.0f,
            exposureEv = 0.0f,
            baseLook = BaseLook.LINEAR_NEUTRAL
        )
        assertEquals(0.583831f, neutral[0], 1e-5f)

        val exposed = CameraDefaultLookMath.renderLinearToSrgb(
            linearRed = 0.6f,
            linearGreen = 0.6f,
            linearBlue = 0.6f,
            autoBrightGain = 1.0f,
            exposureEv = 1.0f,
            baseLook = BaseLook.LINEAR_NEUTRAL
        )
        assertEquals(0.998770f, exposed[0], 1e-5f)
    }

    @Test
    fun shouldStayWithinMeanDeltaE2000Tolerance_whenCameraDefaultMatchesBrowsePath() {
        val samples = buildLinearSamples()
        val gains = floatArrayOf(1.0f, 2.0f, 4.0f)
        var deltaSum = 0.0
        var count = 0
        for (gain in gains) {
            for (sample in samples) {
                val browse = browsePathSrgb8(sample, gain)
                val editor = editorPathSrgb8(sample, gain)
                val browseLab = ColorDeltaE2000.fromSrgb8(browse[0], browse[1], browse[2])
                val editorLab = ColorDeltaE2000.fromSrgb8(editor[0], editor[1], editor[2])
                deltaSum += ColorDeltaE2000.deltaE2000(browseLab, editorLab)
                count++
            }
        }
        val meanDeltaE = deltaSum / count
        assertTrue("Mean Delta E 2000 was $meanDeltaE, expected <= 1.0", meanDeltaE <= 1.0)
    }

    private fun chroma(rgb: FloatArray): Float {
        val max = maxOf(rgb[0], rgb[1], rgb[2])
        val min = minOf(rgb[0], rgb[1], rgb[2])
        return max - min
    }

    private fun buildLinearSamples(): List<FloatArray> {
        val samples = mutableListOf<FloatArray>()
        for (step in 0..32) {
            val v = step / 32.0f
            samples += floatArrayOf(v, v, v)
        }
        samples += floatArrayOf(0.60f, 0.25f, 0.12f)
        samples += floatArrayOf(0.25f, 0.50f, 0.15f)
        samples += floatArrayOf(0.12f, 0.25f, 0.62f)
        samples += floatArrayOf(0.55f, 0.06f, 0.50f)
        samples += floatArrayOf(0.02f, 0.03f, 0.06f)
        samples += floatArrayOf(0.90f, 0.75f, 0.55f)
        return samples
    }

    /** Mirrors `writeDirectPixels` + `applyVibranceAndStoreRgb` from `raw_postprocess.cpp`. */
    private fun browsePathSrgb8(linear: FloatArray, gain: Float): IntArray {
        val displayed = DoubleArray(3) { index ->
            val srgb16 = (65536.0 * referenceSrgbEncode((linear[index] * gain).toDouble()))
                .roundToInt()
                .coerceAtMost(65535)
            referenceToneCurve(srgb16 / 65535.0) * 255.0
        }
        return referenceVibrance(displayed)
    }

    /** Mirrors the editor GL pipeline at zero exposure with `camera_default` enabled. */
    private fun editorPathSrgb8(linear: FloatArray, gain: Float): IntArray {
        val rendered = CameraDefaultLookMath.renderLinearToSrgb(
            linearRed = linear[0],
            linearGreen = linear[1],
            linearBlue = linear[2],
            autoBrightGain = gain,
            exposureEv = 0.0f,
            baseLook = BaseLook.CAMERA_DEFAULT
        )
        return IntArray(3) { (rendered[it] * 255.0f).roundToInt().coerceIn(0, 255) }
    }

    private fun referenceToneCurve(x: Double): Double {
        val clamped = x.coerceIn(0.0, 1.0)
        val lifted = Math.pow(clamped, 0.78)
        val sCurve = lifted * lifted * (3.0 - 2.0 * lifted)
        return 0.88 * lifted + 0.12 * sCurve
    }

    private fun referenceVibrance(rgb: DoubleArray): IntArray {
        val luma = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
        val shadowFade = ((luma - 12.0) / 48.0).coerceIn(0.0, 1.0)
        val maxC = rgb.max()
        val minC = rgb.min()
        val saturation = (maxC - minC) / 255.0
        val scale = 1.0 + 0.18 * shadowFade * (1.0 - saturation).coerceAtLeast(0.0)
        return IntArray(3) { index ->
            val value = luma + (rgb[index] - luma) * scale
            (value.coerceIn(0.0, 255.0) + 0.5).toInt()
        }
    }

    private fun referenceSrgbEncode(value: Double): Double {
        val clamped = value.coerceIn(0.0, 1.0)
        return if (clamped <= 0.0031308) {
            clamped * 12.92
        } else {
            1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055
        }
    }
}
