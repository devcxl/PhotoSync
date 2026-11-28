package cn.devcxl.photosync.editor.ui

import kotlin.math.hypot

import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokePoint
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Maps the visible (post-Pass A) brush frame onto the pre-crop source frame that inpainting patches
 * live in.
 *
 * The inpainting brush paints on the frame the GL pass renders — after crop, rotate, straighten and
 * perspective — while `EditGraph.inpaint` boxes are normalized against the untouched pre-crop
 * source. A stroke therefore has to travel back through the same `H^-1` the shader samples, and its
 * radius has to shrink or grow with the local `d(preCropU)/d(outputU)` derivative. The composed
 * liquify/portrait displacement is applied after the homography, exactly like `uWarpTex` in Pass A.
 *
 * Passing strokes through this projection is what keeps an erase on the right object after the
 * user has cropped or rotated the photo.
 */
internal class InpaintStrokeProjection(
    private val inverseMatrix: Mat3,
    private val srcAspect: Float,
    private val warpField: WarpDisplacementField?
) {

    private val safeAspect: Float
        get() = srcAspect.takeIf { it.isFinite() && it > MIN_SCALE } ?: 1.0f

    /** Projects one visible UV into pre-crop source UV, including the active warp. */
    fun toSource(uv: Uv): Uv {
        val preCrop = inverseMatrix.map(uv.u, uv.v)
        val warp = warpField?.takeUnless { it.isZero() } ?: return preCrop
        val displacement = warp.displacementAt(preCrop.u, preCrop.v)
        return Uv(preCrop.u + displacement.u, preCrop.v + displacement.v)
    }

    /**
     * Local scale of the pre-crop pixel frame relative to the visible frame at [uv].
     *
     * The derivative is probed on the homography only; a smooth liquify displacement changes the
     * brush radius by well under one pixel at the brush sizes the panel allows. The `v` component
     * is weighted by [srcAspect], because the brush radius is a fraction of the source **width**:
     * a 90 degree rotation maps the visible width onto the source height, and ignoring that would
     * make the erase circle wider than the finger by the photo's aspect ratio.
     */
    fun radiusScaleAt(uv: Uv): Float {
        val origin = inverseMatrix.map(uv.u, uv.v)
        val shifted = inverseMatrix.map(uv.u + PROBE_DELTA, uv.v)
        val scale = hypot(
            shifted.u - origin.u,
            (shifted.v - origin.v) * safeAspect
        ) / PROBE_DELTA
        return scale.takeIf { it.isFinite() && it > MIN_SCALE } ?: 1.0f
    }

    /**
     * Projects every visible stroke into pre-crop normalized coordinates.
     *
     * A visible stroke keeps one radius, but the crop/perspective derivative can differ along it.
     * Each segment therefore becomes its own two-point stroke with the average local radius, which
     * keeps the painted capsule continuous while letting the radius follow the geometry.
     */
    fun projectStrokes(strokes: List<InpaintStroke>): List<InpaintStroke> =
        strokes.flatMap(::projectStroke)

    private fun projectStroke(stroke: InpaintStroke): List<InpaintStroke> {
        val points = stroke.points
        if (points.size == 1) {
            val point = points.first()
            return listOf(
                InpaintStroke(
                    erase = stroke.erase,
                    radiusNorm = scaledRadius(stroke.radiusNorm, point),
                    points = listOf(projectPoint(point))
                )
            )
        }
        val projected = ArrayList<InpaintStroke>(points.size - 1)
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            val scale = (radiusAt(previous) + radiusAt(current)) / 2.0f
            projected.add(
                InpaintStroke(
                    erase = stroke.erase,
                    radiusNorm = (stroke.radiusNorm * scale).coerceAtLeast(MIN_RADIUS_NORM),
                    points = listOf(projectPoint(previous), projectPoint(current))
                )
            )
        }
        return projected
    }

    private fun projectPoint(point: InpaintStrokePoint): InpaintStrokePoint {
        val source = toSource(Uv(point.u, point.v))
        return InpaintStrokePoint(source.u, source.v)
    }

    private fun radiusAt(point: InpaintStrokePoint): Float =
        radiusScaleAt(Uv(point.u, point.v))

    private fun scaledRadius(radiusNorm: Float, point: InpaintStrokePoint): Float =
        (radiusNorm * radiusAt(point)).coerceAtLeast(MIN_RADIUS_NORM)

    companion object {
        private const val PROBE_DELTA = 0.01f
        private const val MIN_SCALE = 1e-4f
        private const val MIN_RADIUS_NORM = 1e-6f

        /**
         * Builds the projection for [geometry] and the upright source's [srcAspect].
         *
         * @param warpField Composed face/body/liquify displacement, or `null` when the photo has no
         *   active warp.
         */
        fun of(
            geometry: GeometryOps,
            srcAspect: Float,
            warpField: WarpDisplacementField? = null
        ): InpaintStrokeProjection = InpaintStrokeProjection(
            inverseMatrix = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect),
            srcAspect = srcAspect,
            warpField = warpField
        )
    }
}
