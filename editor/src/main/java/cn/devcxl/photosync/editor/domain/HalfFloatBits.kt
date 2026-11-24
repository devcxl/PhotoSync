package cn.devcxl.photosync.editor.domain

/**
 * IEEE 754 binary16 (`half_float`) codec matching the layout `GL_HALF_FLOAT` texel uploads expect.
 *
 * The `:libraw` module keeps an equivalent codec for its native transfer path, but it is module
 * internal and this one additionally owns the `ShortArray` conversions the liquify grid needs, so
 * the two cannot diverge in behaviour while staying in separate modules.
 */
internal object HalfFloatBits {

    /** Bits of the smallest positive half-float subnormal, `2^-24`. */
    private const val SUBNORMAL_STEP = 5.9604645E-8f

    private const val SIGN_MASK = 0x8000
    private const val EXPONENT_MASK = 0x7C00
    private const val MANTISSA_MASK = 0x03FF
    private const val EXPONENT_ALL_ONES = 0x1F
    private const val NAN_MANTISSA = 0x0200

    /**
     * Encodes [value] with round-to-nearest-even, saturating to infinity on overflow and to a
     * signed zero on underflow.
     */
    fun toBits(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and SIGN_MASK
        val exponent = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7F_FFFF
        if (exponent == 0xFF) {
            val payload = if (mantissa == 0) 0 else NAN_MANTISSA or (mantissa ushr 13)
            return (sign or EXPONENT_MASK or payload).toShort()
        }
        val unbiased = exponent - 127
        if (unbiased > 15) return (sign or EXPONENT_MASK).toShort()
        if (unbiased >= -14) {
            val half = ((unbiased + 15) shl 10) or (mantissa ushr 13)
            return (sign or roundToNearestEven(half, mantissa and 0x1FFF, 13)).toShort()
        }
        if (unbiased < -24) return sign.toShort()
        val shift = -(unbiased + 1)
        val withHiddenBit = mantissa or 0x80_0000
        val subnormal = withHiddenBit ushr shift
        val remainder = withHiddenBit and ((1 shl shift) - 1)
        return (sign or roundToNearestEven(subnormal, remainder, shift)).toShort()
    }

    /** Decodes half-float [bits] back into a `Float`. */
    fun fromBits(bits: Short): Float {
        val raw = bits.toInt() and 0xFFFF
        val sign = if (raw and SIGN_MASK != 0) -1.0f else 1.0f
        val exponent = (raw ushr 10) and EXPONENT_ALL_ONES
        val mantissa = raw and MANTISSA_MASK
        return when (exponent) {
            0 -> if (mantissa == 0) {
                sign * 0.0f
            } else {
                sign * mantissa.toFloat() * SUBNORMAL_STEP
            }
            EXPONENT_ALL_ONES -> if (mantissa == 0) {
                sign * Float.POSITIVE_INFINITY
            } else {
                Float.NaN
            }
            else -> sign * (1024 + mantissa).toFloat() * pow2(exponent - 25)
        }
    }

    /** Decodes every element of [bits]. */
    fun toFloats(bits: ShortArray): FloatArray = FloatArray(bits.size) { fromBits(bits[it]) }

    private fun roundToNearestEven(truncated: Int, remainder: Int, shift: Int): Int {
        if (shift <= 0) return truncated
        val halfway = 1 shl (shift - 1)
        val roundUp = remainder > halfway || (remainder == halfway && (truncated and 1) != 0)
        return if (roundUp) truncated + 1 else truncated
    }

    private fun pow2(exponent: Int): Float = java.lang.Math.pow(2.0, exponent.toDouble()).toFloat()
}
