package cn.devcxl.photosync.editor.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.FaceLandmarkIndices.LEFT_IRIS
import cn.devcxl.photosync.editor.domain.FaceLandmarkIndices.RIGHT_IRIS

/**
 * Seam under test: [SkinMaskMath] and [SkinSmoothingMath] — the dual-mode `skinMask` and the Pass C
 * Stage 0 frequency separation it gates.
 */
class SkinMaskMathTest {

    private val face = FaceMeshFixtures.face(
        centreU = 0.5f,
        centreV = 0.45f,
        width = 0.30f,
        height = 0.34f
    )
    private val skinColour = floatArrayOf(0.82f, 0.66f, 0.56f)

    @Test
    fun shouldProtectEyesBrowsAndLips_whenRasterizingTheHoleMask() {
        val holeMask = SkinMaskMath.holeMask(listOf(face))

        assertEquals(SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE, holeMask.size)
        assertTrue("the right eye must be protected", sample(holeMask, face.uv(RIGHT_IRIS)) > 0.9f)
        assertTrue("the left eye must be protected", sample(holeMask, face.uv(LEFT_IRIS)) > 0.9f)
        assertTrue("the lips must be protected", sample(holeMask, lipsCentre()) > 0.9f)
        assertTrue("the brow must be protected", sample(holeMask, face.uv(105)) > 0.9f)
        assertTrue("the cheek must stay free", sample(holeMask, Uv(0.5f, 0.56f)) < 1e-3f)
    }

    @Test
    fun shouldZeroTheSkinMaskInsideProtectedHoles_whenTheFaceIsDetected() {
        val mask = onlineMask(holeMask = SkinMaskMath.holeMask(listOf(face)))

        assertTrue(sample(mask, face.uv(RIGHT_IRIS)) < 0.05f)
        assertTrue(sample(mask, lipsCentre()) < 0.05f)
        assertTrue(sample(mask, Uv(0.5f, 0.56f)) > 0.85f)
    }

    @Test
    fun shouldExcludeHairAndBackground_whenComposingTheSixClassMask() {
        val size = SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE
        val bodySkin = FloatArray(size) { 1.0f }
        val faceSkin = FloatArray(size) { 0.0f }
        val hair = FloatArray(size) { 0.0f }
        hair.fill(1.0f, 0, size / 2)

        val mask = SkinMaskMath.skinMaskFromClasses(
            bodySkin, faceSkin, hair, holeMask = null, srgb = skinFrame()
        )

        assertTrue("hair must not be smoothed", mask[size / 4] < 0.02f)
        assertTrue("body skin must be smoothed", mask[size * 3 / 4] > 0.85f)
    }

    @Test
    fun shouldAgreeBetweenOnlineAndOfflineModes_whenThePersonMaskEqualsTheClassUnion() {
        val size = SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE
        val bodySkin = FloatArray(size) { 1.0f }
        val faceSkin = FloatArray(size) { 0.2f }
        val hair = FloatArray(size) { 0.1f }
        val hole = SkinMaskMath.holeMask(listOf(face))
        val srgb = skinFrame()
        val person = SkinMaskMath.combinedSkinProbability(bodySkin, faceSkin, hair)

        val online = SkinMaskMath.skinMaskFromClasses(bodySkin, faceSkin, hair, hole, srgb)
        val offline = SkinMaskMath.skinMaskFromPerson(person, hole, srgb)

        for (index in online.indices) {
            assertEquals(index.toString(), 0.0f, abs(online[index] - offline[index]), 1e-4f)
        }
    }

    @Test
    fun shouldKeepTheOfflineGateNarrower_whenThePixelIsOnlyBarelySkinColoured() {
        // An exact OKLCH colour just above the online chroma floor but below the offline one.
        val barelyWarm = oklch(lightness = 0.7f, chroma = 0.014f, hueDegrees = 50.0f)

        val online = SkinMaskMath.skinColorGate(
            barelyWarm[0], barelyWarm[1], barelyWarm[2], strict = false
        )
        val offline = SkinMaskMath.skinColorGate(
            barelyWarm[0], barelyWarm[1], barelyWarm[2], strict = true
        )

        assertTrue("the online gate must accept a barely warm pixel", online > 0.8f)
        assertTrue("the strict gate must reject it", offline < online * 0.8f)
        assertTrue(
            "a neutral gray must never be skin",
            SkinMaskMath.skinColorGate(0.5f, 0.5f, 0.5f, false) < 1e-3f
        )
    }

    @Test
    fun shouldRemoveLuminanceDetailInsideTheSkinMaskOnly_whenSmoothingSkin() {
        val width = 64
        val height = 64
        val before = noisySkinFrame(width, height)
        val after = before.copyOf()
        val mask = leftHalfMask()

        SkinSmoothingMath.apply(
            srgb = after,
            width = width,
            height = height,
            skinMask = mask,
            smooth = 1.0f,
            texture = 0.0f,
            evenTone = 0.0f
        )

        assertTrue("skin detail must be smoothed", variance(after, width, height, leftHalf = true) <
            0.35f * variance(before, width, height, leftHalf = true))
        assertEquals(
            "the background half must be untouched",
            variance(before, width, height, leftHalf = false),
            variance(after, width, height, leftHalf = false),
            1e-6f
        )
    }

    @Test
    fun shouldKeepMoreDetail_whenTheTextureSliderIsRaised() {
        val width = 64
        val height = 64
        val before = noisySkinFrame(width, height)
        val flat = before.copyOf()
        val textured = before.copyOf()
        val mask = leftHalfMask()

        SkinSmoothingMath.apply(flat, width, height, mask, smooth = 1.0f, texture = 0.0f, evenTone = 0.0f)
        SkinSmoothingMath.apply(textured, width, height, mask, smooth = 1.0f, texture = 0.8f, evenTone = 0.0f)

        assertTrue(
            "texture retention must preserve more micro contrast",
            variance(textured, width, height, leftHalf = true) > variance(flat, width, height, leftHalf = true)
        )
    }

    @Test
    fun shouldSuppressSharpeningInsideTheSkinMask_whenTheMaskIsBound() {
        val width = 16
        val height = 16
        val pixels = stepEdge(width, height)
        val edges = pixels.copyOf()

        val params = SpatialDetailParams(sharpenAmount = 1.0f, sharpenRadiusPx = 1.0f)
        val sharpened = edges.copyOf()
        PassCDetailMath.apply(sharpened, width, height, params, skinMask = null)
        val suppressed = pixels.copyOf()
        PassCDetailMath.apply(
            suppressed,
            width,
            height,
            params,
            skinMask = FloatArray(SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE) { 1.0f }
        )

        assertTrue("the unsharp mask must add contrast", contrast(sharpened) > contrast(pixels))
        assertEquals(
            "a fully skin pixel must not be sharpened",
            contrast(pixels),
            contrast(suppressed),
            1e-5f
        )
    }

    private fun onlineMask(holeMask: FloatArray): FloatArray {
        val size = SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE
        return SkinMaskMath.skinMaskFromClasses(
            bodySkin = FloatArray(size) { 1.0f },
            faceSkin = FloatArray(size) { 0.0f },
            hair = FloatArray(size) { 0.0f },
            holeMask = holeMask,
            srgb = skinFrame()
        )
    }

    private fun skinFrame(): FloatArray =
        FloatArray(SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE * 3) { index ->
            skinColour[index % 3]
        }

    private fun leftHalfMask(): FloatArray = FloatArray(SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE) {
        if (it % SkinMaskMath.MASK_SIZE < SkinMaskMath.MASK_SIZE / 2) 1.0f else 0.0f
    }

    private fun noisySkinFrame(width: Int, height: Int): FloatArray {
        val pixels = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 3
                val noise = if ((x + y) % 2 == 0) 0.08f else -0.08f
                pixels[offset] = skinColour[0] + noise
                pixels[offset + 1] = skinColour[1] + noise
                pixels[offset + 2] = skinColour[2] + noise
            }
        }
        return pixels
    }

    private fun stepEdge(width: Int, height: Int): FloatArray {
        val pixels = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (x < width / 2) 0.4f else 0.6f
                val offset = (y * width + x) * 3
                pixels[offset] = value
                pixels[offset + 1] = value
                pixels[offset + 2] = value
            }
        }
        return pixels
    }

    /** Total variation of the red channel: the observable quantity an unsharp mask raises. */
    private fun contrast(pixels: FloatArray): Float {
        var sum = 0.0f
        var index = 3
        while (index < pixels.size) {
            sum += abs(pixels[index] - pixels[index - 3])
            index += 3
        }
        return sum
    }

    private fun lipsCentre(): Uv {
        val upper = face.uv(FaceLandmarkIndices.UPPER_LIP_CENTRE)
        val lower = face.uv(FaceLandmarkIndices.LOWER_LIP_CENTRE)
        return Uv((upper.u + lower.u) * 0.5f, (upper.v + lower.v) * 0.5f)
    }

    private fun oklch(lightness: Float, chroma: Float, hueDegrees: Float): FloatArray {
        val hue = (hueDegrees * PI / 180.0).toFloat()
        val lab = floatArrayOf(lightness, chroma * cos(hue), chroma * sin(hue))
        val linear = FloatArray(3)
        OklabColorMath.oklabToLinearSrgb(lab, linear)
        return FloatArray(3) { ExposureShaderMath.encodeSrgb(linear[it].coerceIn(0.0f, 1.0f)) }
    }

    private fun variance(
        pixels: FloatArray,
        width: Int,
        height: Int,
        leftHalf: Boolean
    ): Float {
        val start = if (leftHalf) 0 else width / 2
        val end = if (leftHalf) width / 2 else width
        var sum = 0.0f
        var sumSquares = 0.0f
        var count = 0
        for (y in 0 until height) {
            for (x in start until end) {
                val value = pixels[(y * width + x) * 3]
                sum += value
                sumSquares += value * value
                count++
            }
        }
        val mean = sum / count
        return sumSquares / count - mean * mean
    }

    private fun sample(mask: FloatArray, uv: Uv): Float =
        SkinMaskMath.sampleMask(mask, SkinMaskMath.MASK_SIZE, uv.u, uv.v)
}
