package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.SubjectBokehMath

/**
 * Builds the terminal Pass C background-bokeh parameters shared by the preview, the offscreen save
 * render and the full-resolution bake.
 *
 * The radius is always derived from the **full-resolution** frame and then scaled to the grid being
 * drawn, so a `45-60 MP` photo shows the same blur fraction on the canvas and in the export. The
 * bake additionally caps its band radius so a halo sample never reads past the band.
 */
internal object SubjectBokehPlanner {

    /**
     * @param blur Graph blur amount in `0..100`; `0` disables the stage.
     * @param shortEdge Short edge of the frame being rendered.
     * @param maskTextureId Uploaded subject alpha; `0` disables the stage.
     * @param fullShortEdge Short edge of the original photo, or [shortEdge] when unchanged.
     * @param maxRadiusPx Upper bound for the bake halo, or [Int.MAX_VALUE] for a full frame.
     */
    fun params(
        blur: Int,
        shortEdge: Int,
        maskTextureId: Int,
        localSpace: Boolean,
        uvMatrix: FloatArray,
        fullShortEdge: Int = shortEdge,
        warpTextureId: Int = 0,
        warpScale: Float = 1.0f,
        warpOffset: Float = 0.0f,
        maxRadiusPx: Int = Int.MAX_VALUE
    ): SubjectBokehDrawParams? {
        if (blur <= 0 || maskTextureId == 0) return null
        val radius = SubjectBokehMath.effectiveRadiusPixels(
            blur = blur,
            fullShortEdge = fullShortEdge,
            renderShortEdge = shortEdge,
            maxRadiusPx = maxRadiusPx
        )
        if (radius <= 0) return null
        return SubjectBokehDrawParams(
            maskTextureId = maskTextureId,
            radiusPx = radius.toFloat(),
            localSpace = localSpace,
            uvMatrix = uvMatrix,
            warpTextureId = warpTextureId,
            warpScale = warpScale,
            warpOffset = warpOffset
        )
    }
}
