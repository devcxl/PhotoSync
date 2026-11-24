package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Solves the linear-sRGB Bradford chromatic adaptation matrix behind the temperature and tint
 * sliders.
 *
 * The model is *relative* and strictly anchored to the sRGB D65 white point: the slider offset is
 * measured in CIE 1960 `uv` along the Planckian locus (temperature) and perpendicular to it (tint),
 * then re-applied as a delta on top of D65:
 *
 * ```
 * uv_target = uv_D65 + (uv_t - uv_0)
 * adapt     = M_A⁻¹ · diag(LMS_target / LMS_D65) · M_A
 * ```
 *
 * At `(temp = 0, tint = 0)` the delta is zero, so `LMS_target == LMS_D65`, `adapt` is the identity
 * matrix and a zeroed slider pair cannot tint the image. Using D65 itself as the anchor (instead of
 * the 6500 K Planckian point, which sits about `+0.0032` off the locus in `v`) is what makes that
 * guarantee exact instead of a magenta cast.
 *
 * Directions follow the photographic convention: positive temperature warms the frame, negative
 * cools it; positive tint shifts magenta, negative shifts green.
 */
object BradfordWbSolver {

    /** Smallest slider value accepted by [adaptationMatrix] and [adaptLinearRgb]. */
    const val SLIDER_MIN = -100

    /** Largest slider value accepted by [adaptationMatrix] and [adaptLinearRgb]. */
    const val SLIDER_MAX = 100

    /** Maximum allowed deviation from the identity matrix when both sliders are zero. */
    const val IDENTITY_TOLERANCE = 1e-6f

    // +60 mired at a full warm slider equals roughly 4680 K, -60 mired about 10650 K.
    private const val REFERENCE_KELVIN = 6500.0
    private const val MIRED_SPAN = 60.0
    private const val MIRED_SCALE = 1e6

    // Full-scale tint moves 0.02 in CIE 1960 v, the customary magnitude of a strong green/magenta
    // camera filter. The shift is applied along the locus normal.
    private const val TINT_UV_SPAN = 0.02
    private const val NORMAL_STEP_MIRED = 1.0

    // The Kim et al. cubic approximation of the Planckian locus is only valid in this window.
    private const val MIN_KELVIN = 1667.0
    private const val MAX_KELVIN = 25000.0

    private const val XY_TO_UV_DENOMINATOR_FLOOR = 1e-6
    private const val UV_TO_XYZ_MIN_V = 1e-4

    private val D65_XYZ = doubleArrayOf(0.95047, 1.0, 1.08883)

    private val SRGB_TO_XYZ = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041
    )

    /** Exact numerical inverse of [SRGB_TO_XYZ], so a zero adaptation round-trips bit-for-bit. */
    private val XYZ_TO_SRGB = invert3x3(SRGB_TO_XYZ)

    /** Bradford cone-response matrix (Lam 1985). */
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296
    )

    private val BRADFORD_INVERSE = invert3x3(BRADFORD)

    private val UV_D65 = xyzToUv(D65_XYZ)
    private val UV_REFERENCE = planckianUv(REFERENCE_KELVIN)

    /**
     * Returns the linear-sRGB adaptation matrix in the column-major `FloatArray(9)` layout GLSL
     * expects for a `mat3` uniform (the same layout [Mat3.toColumnMajorFloatArray] produces).
     *
     * @param temp Temperature slider, clamped to [SLIDER_MIN]..[SLIDER_MAX]; positive warms.
     * @param tint Tint slider, clamped to [SLIDER_MIN]..[SLIDER_MAX]; positive shifts magenta.
     */
    fun adaptationMatrix(temp: Int, tint: Int): FloatArray {
        val rowMajor = srgbAdaptationRowMajor(temp, tint)
        return FloatArray(9) { index ->
            val row = index % 3
            val column = index / 3
            rowMajor[row * 3 + column].toFloat()
        }
    }

    /**
     * Applies the adaptation matrix to one linear-light sRGB sample.
     *
     * @param red Linear-light red of the source sample.
     * @param green Linear-light green of the source sample.
     * @param blue Linear-light blue of the source sample.
     * @param temp Temperature slider, clamped to [SLIDER_MIN]..[SLIDER_MAX]; positive warms.
     * @param tint Tint slider, clamped to [SLIDER_MIN]..[SLIDER_MAX]; positive shifts magenta.
     * @return Three adapted linear-light channels; the caller still has to clamp them.
     */
    fun adaptLinearRgb(red: Float, green: Float, blue: Float, temp: Int, tint: Int): FloatArray {
        val matrix = srgbAdaptationRowMajor(temp, tint)
        return floatArrayOf(
            (matrix[0] * red + matrix[1] * green + matrix[2] * blue).toFloat(),
            (matrix[3] * red + matrix[4] * green + matrix[5] * blue).toFloat(),
            (matrix[6] * red + matrix[7] * green + matrix[8] * blue).toFloat()
        )
    }

    /**
     * Builds the XYZ-space Bradford adaptation that maps [sourceXyz] white onto [targetXyz] white.
     *
     * Row-major and `internal` so the cone-response oracle test can compare it against a published
     * Bradford matrix; the same helper is what a future "as shot" white point would reuse.
     */
    internal fun adaptationInXyzBetweenWhitePoints(
        sourceXyz: FloatArray,
        targetXyz: FloatArray
    ): FloatArray {
        require(sourceXyz.size == 3 && targetXyz.size == 3) { "White points must be XYZ triplets" }
        val rowMajor = adaptationInXyzBetweenWhitePoints(sourceXyz.toDoubles(), targetXyz.toDoubles())
        return FloatArray(9) { rowMajor[it].toFloat() }
    }

    private fun adaptationInXyzBetweenWhitePoints(
        sourceXyz: DoubleArray,
        targetXyz: DoubleArray
    ): DoubleArray {
        val sourceLms = BRADFORD.multiplyBy(sourceXyz)
        val targetLms = BRADFORD.multiplyBy(targetXyz)
        val coneGain = doubleArrayOf(
            targetLms[0] / sourceLms[0], 0.0, 0.0,
            0.0, targetLms[1] / sourceLms[1], 0.0,
            0.0, 0.0, targetLms[2] / sourceLms[2]
        )
        return BRADFORD_INVERSE.times(coneGain).times(BRADFORD)
    }

    private fun srgbAdaptationRowMajor(temp: Int, tint: Int): DoubleArray {
        val temperature = temp.coerceIn(SLIDER_MIN, SLIDER_MAX) / 100.0
        val tintValue = tint.coerceIn(SLIDER_MIN, SLIDER_MAX) / 100.0
        val xyzAdaptation = adaptationInXyzBetweenWhitePoints(
            sourceXyz = D65_XYZ,
            targetXyz = targetWhiteXyz(temperature, tintValue)
        )
        return XYZ_TO_SRGB.times(xyzAdaptation).times(SRGB_TO_XYZ)
    }

    private fun targetWhiteXyz(temperature: Double, tint: Double): DoubleArray {
        val mired = MIRED_SCALE / REFERENCE_KELVIN + MIRED_SPAN * temperature
        val kelvin = miredToKelvin(mired)
        val locus = planckianUv(kelvin)
        val tintU: Double
        val tintV: Double
        if (tint == 0.0) {
            tintU = 0.0
            tintV = 0.0
        } else {
            val normal = locusNormal(kelvin)
            tintU = tint * TINT_UV_SPAN * normal[0]
            tintV = tint * TINT_UV_SPAN * normal[1]
        }
        val targetU = UV_D65[0] + (locus[0] + tintU) - UV_REFERENCE[0]
        val targetV = UV_D65[1] + (locus[1] + tintV) - UV_REFERENCE[1]
        return uvToXyz(targetU, targetV)
    }

    /**
     * Unit normal of the Planckian locus in CIE 1960 `uv`, pointing towards magenta.
     *
     * The locus runs towards larger `u` and `v` as mired grows, so `(dv, -du)` is the normal with a
     * negative `v` component, which is the green -> magenta side of the locus.
     */
    private fun locusNormal(kelvin: Double): DoubleArray {
        val mired = MIRED_SCALE / kelvin
        val cool = planckianUv(miredToKelvin(mired - NORMAL_STEP_MIRED))
        val warm = planckianUv(miredToKelvin(mired + NORMAL_STEP_MIRED))
        val deltaU = warm[0] - cool[0]
        val deltaV = warm[1] - cool[1]
        val length = hypot(deltaU, deltaV)
        if (length < XY_TO_UV_DENOMINATOR_FLOOR) return doubleArrayOf(0.0, -1.0)
        return doubleArrayOf(deltaV / length, -deltaU / length)
    }

    private fun miredToKelvin(mired: Double): Double =
        (MIRED_SCALE / mired).coerceIn(MIN_KELVIN, MAX_KELVIN)

    /**
     * CIE 1960 `uv` of the Planckian locus, via the Kim et al. (2002) cubic approximation.
     */
    private fun planckianUv(kelvin: Double): DoubleArray {
        val temperature = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN)
        val cube = temperature * temperature * temperature
        val square = temperature * temperature
        val x = if (temperature <= 4000.0) {
            -0.2661239e9 / cube - 0.2343589e6 / square + 0.8776956e3 / temperature + 0.179910
        } else {
            -3.0258469e9 / cube + 2.1070379e6 / square + 0.2226347e3 / temperature + 0.240390
        }
        val y = when {
            temperature <= 2222.0 ->
                -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            temperature <= 4000.0 ->
                -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else ->
                3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        val denominator = (-2.0 * x + 12.0 * y + 3.0).coerceAtLeast(XY_TO_UV_DENOMINATOR_FLOOR)
        return doubleArrayOf(4.0 * x / denominator, 6.0 * y / denominator)
    }

    private fun xyzToUv(xyz: DoubleArray): DoubleArray {
        val denominator = (xyz[0] + 15.0 * xyz[1] + 3.0 * xyz[2])
            .coerceAtLeast(XY_TO_UV_DENOMINATOR_FLOOR)
        return doubleArrayOf(4.0 * xyz[0] / denominator, 6.0 * xyz[1] / denominator)
    }

    private fun uvToXyz(u: Double, v: Double): DoubleArray {
        val safeV = v.coerceAtLeast(UV_TO_XYZ_MIN_V)
        return doubleArrayOf(3.0 * u / (2.0 * safeV), 1.0, (4.0 - u - 10.0 * safeV) / (2.0 * safeV))
    }

    private fun invert3x3(source: DoubleArray): DoubleArray {
        val a = source[0]
        val b = source[1]
        val c = source[2]
        val d = source[3]
        val e = source[4]
        val f = source[5]
        val g = source[6]
        val h = source[7]
        val i = source[8]
        val cofactorA = e * i - f * h
        val cofactorB = f * g - d * i
        val cofactorC = d * h - e * g
        val determinant = a * cofactorA + b * cofactorB + c * cofactorC
        require(abs(determinant) > 1e-12) { "Matrix is singular" }
        val inverseDeterminant = 1.0 / determinant
        return doubleArrayOf(
            cofactorA * inverseDeterminant,
            (c * h - b * i) * inverseDeterminant,
            (b * f - c * e) * inverseDeterminant,
            cofactorB * inverseDeterminant,
            (a * i - c * g) * inverseDeterminant,
            (c * d - a * f) * inverseDeterminant,
            cofactorC * inverseDeterminant,
            (b * g - a * h) * inverseDeterminant,
            (a * e - b * d) * inverseDeterminant
        )
    }

    private fun DoubleArray.times(other: DoubleArray): DoubleArray {
        val product = DoubleArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0.0
                for (index in 0..2) {
                    sum += this[row * 3 + index] * other[index * 3 + column]
                }
                product[row * 3 + column] = sum
            }
        }
        return product
    }

    private fun DoubleArray.multiplyBy(vector: DoubleArray): DoubleArray = DoubleArray(3) { row ->
        this[row * 3] * vector[0] + this[row * 3 + 1] * vector[1] + this[row * 3 + 2] * vector[2]
    }

    private fun FloatArray.toDoubles(): DoubleArray = DoubleArray(size) { this[it].toDouble() }
}
