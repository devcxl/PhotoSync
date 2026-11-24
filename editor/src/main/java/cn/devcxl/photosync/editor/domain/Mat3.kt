package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

/**
 * Immutable 3x3 homogeneous transform over normalized UV coordinates, stored row-major.
 *
 * Points are column vectors, so `[u, v, 1]` is transformed as `M * [u, v, 1]` and composed as
 * `M_total = M_first * M_second * ...` when [Mat3.times] receives left-to-right operands.
 * Double precision is kept internally so chained geometry stages do not accumulate visible error.
 */
class Mat3 internal constructor(private val values: DoubleArray) {

    init {
        require(values.size == ELEMENT_COUNT) { "A 3x3 matrix needs $ELEMENT_COUNT elements" }
    }

    /**
     * Returns the element at [row], [column].
     */
    operator fun get(row: Int, column: Int): Double = values[row * 3 + column]

    /**
     * Returns the matrix product `this * other`.
     */
    fun times(other: Mat3): Mat3 {
        val product = DoubleArray(ELEMENT_COUNT)
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0.0
                for (index in 0..2) {
                    sum += this[row, index] * other[index, column]
                }
                product[row * 3 + column] = sum
            }
        }
        return Mat3(product)
    }

    /**
     * Applies this transform to the UV point [u], [v], performing the homogeneous perspective
     * divide. Returns [Uv] with zero coordinates when the point maps to infinity.
     */
    fun map(u: Float, v: Float): Uv {
        val x = this[0, 0] * u + this[0, 1] * v + this[0, 2]
        val y = this[1, 0] * u + this[1, 1] * v + this[1, 2]
        val w = this[2, 0] * u + this[2, 1] * v + this[2, 2]
        if (abs(w) < SINGULAR_EPSILON) return Uv(0.0f, 0.0f)
        val inverseW = 1.0 / w
        return Uv((x * inverseW).toFloat(), (y * inverseW).toFloat())
    }

    /**
     * Returns the inverse transform, or `null` when this matrix is singular.
     */
    fun invertOrNull(): Mat3? {
        val a = this[0, 0]
        val b = this[0, 1]
        val c = this[0, 2]
        val d = this[1, 0]
        val e = this[1, 1]
        val f = this[1, 2]
        val g = this[2, 0]
        val h = this[2, 1]
        val i = this[2, 2]
        val determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(determinant) < SINGULAR_EPSILON) return null
        val inverseDeterminant = 1.0 / determinant
        val inverse = doubleArrayOf(
            (e * i - f * h) * inverseDeterminant,
            (c * h - b * i) * inverseDeterminant,
            (b * f - c * e) * inverseDeterminant,
            (f * g - d * i) * inverseDeterminant,
            (a * i - c * g) * inverseDeterminant,
            (c * d - a * f) * inverseDeterminant,
            (d * h - e * g) * inverseDeterminant,
            (b * g - a * h) * inverseDeterminant,
            (a * e - b * d) * inverseDeterminant
        )
        return if (inverse.all { it.isFinite() }) Mat3(inverse) else null
    }

    /**
     * Packs this matrix into the column-major `FloatArray` layout expected by
     * `unchecked { GLES20.glUniformMatrix3fv(location, 1, false, values, 0) }`.
     */
    fun toColumnMajorFloatArray(): FloatArray {
        val packed = FloatArray(ELEMENT_COUNT)
        for (row in 0..2) {
            for (column in 0..2) {
                packed[column * 3 + row] = this[row, column].toFloat()
            }
        }
        return packed
    }

    companion object {
        private const val ELEMENT_COUNT = 9
        private const val SINGULAR_EPSILON = 1e-12

        /**
         * Returns the identity transform.
         */
        fun identity(): Mat3 = Mat3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        internal fun ofRowMajor(values: DoubleArray): Mat3 = Mat3(values.copyOf())
    }
}
