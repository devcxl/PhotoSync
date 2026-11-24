package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

/**
 * Four-point homography solver.
 *
 * Kept separate from [GeometryMath] because it is pure linear algebra: a `8x8` direct linear
 * transform system solved in double precision with partial pivoting, so chained stages do not
 * accumulate visible error before the matrix reaches the shader.
 */
internal object HomographySolver {

    private const val QUAD_CORNERS = 4
    private const val QUAD_COORDINATES = QUAD_CORNERS * 2
    private const val PIVOT_EPSILON = 1e-12

    /**
     * Solves the transform that maps [srcQuad] onto [dstQuad].
     *
     * Quads are flat `[u0, v0, u1, v1, u2, v2, u3, v3]` arrays ordered top-left, top-right,
     * bottom-right, bottom-left.
     *
     * @return The homography, or `null` when the quad is malformed, degenerate or singular.
     */
    fun solve(dstQuad: FloatArray, srcQuad: FloatArray): Mat3? {
        if (!isUsableQuad(dstQuad) || !isUsableQuad(srcQuad)) return null
        val system = Array(QUAD_COORDINATES) { DoubleArray(QUAD_COORDINATES + 1) }
        for (corner in 0 until QUAD_CORNERS) {
            fillRows(system, corner, srcQuad, dstQuad)
        }
        val solution = solveSystem(system) ?: return null
        return Mat3.ofRowMajor(
            doubleArrayOf(
                solution[0], solution[1], solution[2],
                solution[3], solution[4], solution[5],
                solution[6], solution[7], 1.0
            )
        )
    }

    private fun isUsableQuad(quad: FloatArray): Boolean =
        quad.size == QUAD_COORDINATES && quad.all { it.isFinite() }

    private fun fillRows(
        system: Array<DoubleArray>,
        corner: Int,
        src: FloatArray,
        dst: FloatArray
    ) {
        val x = src[corner * 2].toDouble()
        val y = src[corner * 2 + 1].toDouble()
        val targetX = dst[corner * 2].toDouble()
        val targetY = dst[corner * 2 + 1].toDouble()
        val uRow = system[corner * 2]
        uRow[0] = x
        uRow[1] = y
        uRow[2] = 1.0
        uRow[6] = -targetX * x
        uRow[7] = -targetX * y
        uRow[8] = targetX
        val vRow = system[corner * 2 + 1]
        vRow[3] = x
        vRow[4] = y
        vRow[5] = 1.0
        vRow[6] = -targetY * x
        vRow[7] = -targetY * y
        vRow[8] = targetY
    }

    /**
     * Solves `system * x = constants` in place with partial-pivoted Gaussian elimination.
     */
    private fun solveSystem(system: Array<DoubleArray>): DoubleArray? {
        val size = system.size
        for (column in 0 until size) {
            val pivotRow = pivotRowFor(system, column) ?: return null
            swapRows(system, column, pivotRow)
            eliminateBelow(system, column)
        }
        return backSubstitute(system)
    }

    private fun pivotRowFor(system: Array<DoubleArray>, column: Int): Int? {
        var pivotRow = column
        for (row in column + 1 until system.size) {
            if (abs(system[row][column]) > abs(system[pivotRow][column])) pivotRow = row
        }
        return pivotRow.takeIf { abs(system[it][column]) >= PIVOT_EPSILON }
    }

    private fun swapRows(system: Array<DoubleArray>, first: Int, second: Int) {
        if (first == second) return
        val swap = system[first]
        system[first] = system[second]
        system[second] = swap
    }

    private fun eliminateBelow(system: Array<DoubleArray>, column: Int) {
        for (row in column + 1 until system.size) {
            val factor = system[row][column] / system[column][column]
            if (factor == 0.0) continue
            for (entry in column..system.size) {
                system[row][entry] -= factor * system[column][entry]
            }
        }
    }

    private fun backSubstitute(system: Array<DoubleArray>): DoubleArray? {
        val size = system.size
        val solution = DoubleArray(size)
        for (row in size - 1 downTo 0) {
            var sum = system[row][size]
            for (column in row + 1 until size) {
                sum -= system[row][column] * solution[column]
            }
            solution[row] = sum / system[row][row]
        }
        return solution.takeIf { values -> values.all { it.isFinite() } }
    }
}
