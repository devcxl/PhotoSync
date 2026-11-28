package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Builds the frame-aligned subject alpha the CPU Pass B/C reference needs.
 *
 * The GL canvas maps every shader coordinate through the Pass A inverse homography and `uWarpTex`;
 * the CPU preview cache renders in the same post-Pass-A grid, so the source-space
 * [SubjectComposite.alpha] must be sampled with the identical mapping. Precomputing the plane once
 * keeps the per-pixel Pass B grade and the Pass C bokeh taps to a single array lookup.
 */
internal object SubjectWeightPlane {

    /**
     * Resamples [composite]'s alpha onto a [width] x [height] output grid.
     *
     * @param inverseMatrix Output-UV to pre-crop-UV matrix.
     * @param warpField Composed liquify/portrait displacement already applied by Pass A.
     * @return One alpha per output pixel, or `null` when the plane cannot be allocated.
     */
    fun compute(
        composite: SubjectComposite,
        width: Int,
        height: Int,
        inverseMatrix: Mat3 = Mat3.identity(),
        warpField: WarpDisplacementField? = null
    ): FloatArray? {
        if (width <= 0 || height <= 0) return null
        return try {
            val plane = FloatArray(width * height)
            for (row in 0 until height) {
                val v = (row + 0.5f) / height
                for (column in 0 until width) {
                    val u = (column + 0.5f) / width
                    val mapped = inverseMatrix.map(u, v)
                    val warped = warpField?.let { field ->
                        val displacement = field.displacementAt(mapped.u, mapped.v)
                        Uv(mapped.u + displacement.u, mapped.v + displacement.v)
                    } ?: mapped
                    plane[row * width + column] = composite.alphaAt(warped.u, warped.v)
                }
            }
            plane
        } catch (oom: OutOfMemoryError) {
            null
        }
    }
}
