package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

/**
 * Normalized Pass B upper-half uniforms: the D65-anchored white balance matrix plus the eight tone
 * sliders mapped from `-100..100` to `-1..1`.
 *
 * Instances are immutable snapshots, so the GL thread can adopt one per frame without ever seeing a
 * half-updated slider. Build them with [from], never by assembling uniforms by hand.
 */
class ToneShaderParams(
    /** Exposure in stops, applied as `2^EV` on linear light. */
    val exposureEv: Float = 0.0f,
    /** Middle-gray brightness in `-1..1`, weighted by a log-normal falloff around Y = 0.18. */
    val brightness: Float = 0.0f,
    /** Contrast in `-1..1`; non-negative uses an S-curve, negative a mid-gray compression. */
    val contrast: Float = 0.0f,
    /** Ambiance glow in `-1..1`. */
    val glow: Float = 0.0f,
    /** Highlight trim in `-1..1`, applied in linear light before the shoulder roll-off. */
    val highlights: Float = 0.0f,
    /** Shadow trim in `-1..1`, applied in linear light before the shoulder roll-off. */
    val shadows: Float = 0.0f,
    /** White-point trim in `-1..1`, applied in linear light before the shoulder roll-off. */
    val whites: Float = 0.0f,
    /** Blacks toe in `-1..1`, applied on the encoded luminance. */
    val blacks: Float = 0.0f,
    /**
     * Linear-sRGB Bradford adaptation in column-major `mat3` layout; [IDENTITY_MATRIX] when the
     * temperature and tint sliders are neutral.
     */
    val wbMatrix: FloatArray = IDENTITY_MATRIX
) {

    /** True when [wbMatrix] carries an actual adaptation instead of the identity. */
    val hasWhiteBalance: Boolean = !isIdentityMatrix(wbMatrix)

    /** True when this bundle leaves every pixel untouched. */
    val isIdentity: Boolean =
        abs(exposureEv) < NEUTRAL_EPSILON &&
            abs(brightness) < NEUTRAL_EPSILON &&
            abs(contrast) < NEUTRAL_EPSILON &&
            abs(glow) < NEUTRAL_EPSILON &&
            abs(highlights) < NEUTRAL_EPSILON &&
            abs(shadows) < NEUTRAL_EPSILON &&
            abs(whites) < NEUTRAL_EPSILON &&
            abs(blacks) < NEUTRAL_EPSILON &&
            !hasWhiteBalance

    companion object {
        private const val NEUTRAL_EPSILON = 1e-6f
        private const val MATRIX_IDENTITY_TOLERANCE = 1e-6f
        private const val SLIDER_SCALE = 100.0f

        /** Column-major identity, matching the layout of [wbMatrix]. Do not mutate. */
        val IDENTITY_MATRIX: FloatArray = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )

        /**
         * Maps the tone and white-balance sliders of [graph] onto shader uniforms.
         */
        fun from(graph: EditGraph): ToneShaderParams {
            val tone = graph.tone
            val color = graph.color
            val hasWhiteBalance = color.temp != 0 || color.tint != 0
            return ToneShaderParams(
                exposureEv = tone.exposure,
                brightness = tone.brightness.normalized(),
                contrast = tone.contrast.normalized(),
                glow = tone.glow.normalized(),
                highlights = tone.highlights.normalized(),
                shadows = tone.shadows.normalized(),
                whites = tone.whites.normalized(),
                blacks = tone.blacks.normalized(),
                wbMatrix = if (hasWhiteBalance) {
                    BradfordWbSolver.adaptationMatrix(color.temp, color.tint)
                } else {
                    IDENTITY_MATRIX
                }
            )
        }

        private fun Int.normalized(): Float = this / SLIDER_SCALE

        private fun isIdentityMatrix(matrix: FloatArray): Boolean =
            matrix.size == 9 &&
                (0..2).all { axis ->
                    (0..2).all { column ->
                        val expected = if (axis == column) 1.0f else 0.0f
                        val actual = matrix[column * 3 + axis]
                        abs(actual - expected) < MATRIX_IDENTITY_TOLERANCE
                    }
                }
    }
}
