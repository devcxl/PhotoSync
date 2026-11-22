package cn.devcxl.photosync.editor.ui

import kotlin.math.hypot

import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.Uv

/**
 * Maps a visible (post-crop) canvas coordinate onto the pre-crop coordinate the liquify displacement
 * grid is defined in.
 *
 * The mesh is sampled by Pass A with `preCropUv`, i.e. after the inverse crop/straighten/perspective
 * homography. A brush stamp therefore has to travel the same road in reverse: the touch point is
 * pushed through `H^-1`, and its radius is scaled by the local `d(preCropU)/d(outputU)` derivative so
 * the circle the user sees affects exactly that area of the grid.
 */
internal class LiquifyStrokeProjection(
    private val inverseMatrix: Mat3,
    private val srcAspect: Float
) {

    /** Width/height ratio of the pre-crop frame the grid lives in. */
    val frameAspect: Float
        get() = srcAspect.takeIf { it.isFinite() && it > 0.0f } ?: 1.0f

    /** Projects a visible UV into pre-crop UV. */
    fun toPreCrop(uv: Uv): Uv = inverseMatrix.map(uv.u, uv.v)

    /**
     * Local scale of the pre-crop frame relative to the visible frame at [uv].
     *
     * For a half-width crop this is `0.5`, which is exactly what the brush needs: a 10% wide ring on
     * screen covers a 5% wide slice of the source grid.
     */
    fun radiusScaleAt(uv: Uv): Float {
        val origin = inverseMatrix.map(uv.u, uv.v)
        val shifted = inverseMatrix.map(uv.u + PROBE_DELTA, uv.v)
        val scale = hypot(shifted.u - origin.u, shifted.v - origin.v) / PROBE_DELTA
        return scale.takeIf { it.isFinite() && it > MIN_SCALE } ?: 1.0f
    }

    companion object {
        private const val PROBE_DELTA = 0.01f
        private const val MIN_SCALE = 1e-4f

        /**
         * Builds the projection for [geometry] and the upright source's [srcAspect].
         */
        fun of(geometry: GeometryOps, srcAspect: Float): LiquifyStrokeProjection =
            LiquifyStrokeProjection(
                inverseMatrix = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect),
                srcAspect = srcAspect
            )
    }
}
