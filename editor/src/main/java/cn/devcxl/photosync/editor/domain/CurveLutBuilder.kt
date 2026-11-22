package cn.devcxl.photosync.editor.domain

/**
 * Packs the 4-channel Fritsch-Carlson curves (`R`, `G`, `B`, `RGB`) into a `1024 x 1` RGBA 1D LUT
 * for both the GLES `GL_RGBA16F` texture and the CPU preview cache.
 *
 * Texel layout: `.r = R`, `.g = G`, `.b = B`, `.a = RGB composite`.
 */
object CurveLutBuilder {

    const val LUT_SIZE = 1024
    const val CHANNELS = 4
    private const val LAST_INDEX = LUT_SIZE - 1

    /**
     * Builds a `1024 * 4` float LUT in `[r, g, b, rgb]` channel order.
     */
    fun buildFloatLut(curve: CurveOps): FloatArray {
        val compiledR = MonotoneCubicCurve.compile(curve.r)
        val compiledG = MonotoneCubicCurve.compile(curve.g)
        val compiledB = MonotoneCubicCurve.compile(curve.b)
        val compiledRgb = MonotoneCubicCurve.compile(curve.rgb)
        val out = FloatArray(LUT_SIZE * CHANNELS)
        for (i in 0 until LUT_SIZE) {
            val x = i.toFloat() / LAST_INDEX.toFloat()
            val base = i * CHANNELS
            out[base] = compiledR.evaluate(x)
            out[base + 1] = compiledG.evaluate(x)
            out[base + 2] = compiledB.evaluate(x)
            out[base + 3] = compiledRgb.evaluate(x)
        }
        return out
    }

    /**
     * Builds a `1024 * 4` IEEE 754 binary16 LUT ready for `GL_RGBA16F` upload.
     */
    fun buildHalfFloatLut(curve: CurveOps): ShortArray {
        val floats = buildFloatLut(curve)
        return ShortArray(floats.size) { index -> HalfFloatBits.toBits(floats[index]) }
    }

    /**
     * Samples [channelOffset] (`0=R`, `1=G`, `2=B`, `3=RGB`) from [lut] with linear interpolation
     * matching `GL_LINEAR` texture sampling.
     */
    fun sampleChannel(lut: FloatArray, channelOffset: Int, x: Float): Float {
        val clamped = x.coerceIn(0.0f, 1.0f)
        val scaled = clamped * LAST_INDEX.toFloat()
        val lo = scaled.toInt().coerceIn(0, LAST_INDEX)
        val hi = (lo + 1).coerceAtMost(LAST_INDEX)
        val frac = scaled - lo
        val v0 = lut[lo * CHANNELS + channelOffset]
        val v1 = lut[hi * CHANNELS + channelOffset]
        return v0 + (v1 - v0) * frac
    }

    /**
     * Applies RGB composite first, then individual R/G/B channels to [srgb] in place (`out` may alias [srgb]).
     */
    fun applyCurve(lut: FloatArray, srgb: FloatArray, out: FloatArray) {
        val compR = sampleChannel(lut, 3, srgb[0])
        val compG = sampleChannel(lut, 3, srgb[1])
        val compB = sampleChannel(lut, 3, srgb[2])
        out[0] = sampleChannel(lut, 0, compR)
        out[1] = sampleChannel(lut, 1, compG)
        out[2] = sampleChannel(lut, 2, compB)
    }
}
