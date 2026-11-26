package cn.devcxl.photosync.editor.domain

/**
 * A Post-Pass A cloud layer already converted into the scene-linear encoding the CPU Pass B expects.
 *
 * The 16-bit preview path resamples geometry into a linear frame and then runs `BaseLook` and
 * `PassBColorMath` on it. Mounting the cloud layer between those two stages — instead of blending
 * display pixels afterwards — is what makes local exposure, white balance, tone, curve and LUT act on
 * the cloud result exactly like they act on the camera pixels around it.
 *
 * @property width Frame width the layer was resampled to.
 * @property height Frame height the layer was resampled to.
 * @property linearRgb Straight linear RGB, three floats per pixel, top-down row-major.
 * @property coverage Optional per-pixel coverage in `0..1`; `null` means the whole frame.
 * @property opacity Effective layer opacity in `0..1`.
 */
class LinearCloudLayer(
    val width: Int,
    val height: Int,
    val linearRgb: FloatArray,
    val coverage: FloatArray? = null,
    val opacity: Float = 1.0f
) {

    init {
        require(width > 0 && height > 0) { "Cloud layer dimensions must be positive" }
        require(linearRgb.size == width * height * CHANNELS) {
            "Cloud layer buffer does not match its dimensions"
        }
        require(coverage == null || coverage.size == width * height) {
            "Cloud layer coverage does not match its dimensions"
        }
    }

    /** Blend factor of row-major [index] after opacity, coverage and clamping. */
    fun factorAt(index: Int): Float {
        val mask = coverage?.getOrNull(index) ?: 1.0f
        return (opacity * mask.coerceIn(0.0f, 1.0f)).coerceIn(0.0f, 1.0f)
    }

    /**
     * Blends the layer into the scene-linear pixel [linear] (`rgb`, 3 floats) in place.
     *
     * @param index Row-major pixel index of the destination frame.
     */
    fun blendInto(index: Int, linear: FloatArray) {
        val factor = factorAt(index)
        if (factor <= 0.0f) return
        val source = index * CHANNELS
        val inverse = 1.0f - factor
        linear[0] = linear[0] * inverse + linearRgb[source] * factor
        linear[1] = linear[1] * inverse + linearRgb[source + 1] * factor
        linear[2] = linear[2] * inverse + linearRgb[source + 2] * factor
    }

    private companion object {
        const val CHANNELS = 3
    }
}
