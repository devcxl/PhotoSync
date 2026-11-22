package cn.devcxl.photosync.editor.domain

import kotlin.math.max

/** A single-resolution scalar image plane. */
internal class ScalarPlane(val values: FloatArray, val width: Int, val height: Int)

/**
 * The two-level low-frequency luminance guide pyramid shared by Clarity and Texture.
 *
 * The 1024px Pre-pass frame is reduced to a `512px` level (`~0.3%` of the short edge) for the
 * Texture high-pass and a `128px` level (`~2.0%` of the short edge) for the Clarity high-pass. Both
 * levels are then resampled onto the packed Pre-pass grid so Pass C only needs one bilinear fetch
 * per channel, which is what keeps a column-band Bake inside its 64px halo.
 */
internal object LowFrequencyPyramid {

    /** Long edge of the Texture guide level: `~0.3%` of a 1024px short edge. */
    const val TEXTURE_LEVEL_MAX_EDGE = 512

    /** Long edge of the Clarity guide level: `~2.0%` of a 1024px short edge. */
    const val CLARITY_LEVEL_MAX_EDGE = 128

    private const val TEXTURE_SMOOTH_RADIUS = 1
    private const val CLARITY_SMOOTH_RADIUS = 2
    private const val MIN_LEVEL_EDGE = 1

    /**
     * Builds both guide layers on a [grid] x [grid] packed grid.
     *
     * @param luma Luminance of the 1024px Pre-pass frame, row-major.
     */
    fun build(luma: FloatArray, width: Int, height: Int, grid: Int): PackedGuides {
        require(luma.size == width * height) { "Luminance must match the ${width}x$height frame" }
        require(width > 0 && height > 0 && grid > 0) { "Frame and grid sizes must be positive" }
        val texture = downsampleToMaxEdge(luma, width, height, TEXTURE_LEVEL_MAX_EDGE)
        val clarity = downsampleToMaxEdge(luma, width, height, CLARITY_LEVEL_MAX_EDGE)
        val textureBase = resampleToGrid(
            ImageFilters.boxMean(texture.values, texture.width, texture.height, TEXTURE_SMOOTH_RADIUS),
            texture,
            grid
        )
        val clarityBase = resampleToGrid(
            ImageFilters.boxMean(clarity.values, clarity.width, clarity.height, CLARITY_SMOOTH_RADIUS),
            clarity,
            grid
        )
        return PackedGuides(clarityBase = clarityBase, textureBase = textureBase, size = grid)
    }

    /** Two guide layers packed onto the Pre-pass grid. */
    class PackedGuides(
        /** Clarity low-frequency base (`2.0%` short edge). */
        val clarityBase: FloatArray,
        /** Texture low-frequency base (`0.3%` short edge). */
        val textureBase: FloatArray,
        val size: Int
    )

    private fun downsampleToMaxEdge(
        luma: FloatArray,
        width: Int,
        height: Int,
        maxEdge: Int
    ): ScalarPlane {
        var plane = ScalarPlane(luma, width, height)
        while (max(plane.width, plane.height) > maxEdge &&
            plane.width > MIN_LEVEL_EDGE &&
            plane.height > MIN_LEVEL_EDGE
        ) {
            val nextWidth = max(plane.width / 2, MIN_LEVEL_EDGE)
            val nextHeight = max(plane.height / 2, MIN_LEVEL_EDGE)
            if (nextWidth == plane.width && nextHeight == plane.height) break
            val values = ImageFilters.resamplePlane(
                plane.values,
                plane.width,
                plane.height,
                nextWidth,
                nextHeight
            )
            plane = ScalarPlane(values, nextWidth, nextHeight)
        }
        return plane
    }

    private fun resampleToGrid(values: FloatArray, plane: ScalarPlane, grid: Int): FloatArray =
        ImageFilters.resamplePlane(values, plane.width, plane.height, grid, grid)
}
