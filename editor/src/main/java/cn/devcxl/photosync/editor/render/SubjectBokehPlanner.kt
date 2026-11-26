package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.SubjectBokehMath

/**
 * Builds the terminal Pass C background-bokeh parameters shared by the preview, the offscreen save
 * render and the full-resolution bake.
 *
 * Radius policy differs per caller (the preview uses the short edge of its frame, the bake caps the
 * radius so a band halo never samples past the band), so [maxRadiusPx] is a parameter instead of a
 * branch inside each pass chain.
 */
internal object SubjectBokehPlanner {

    /**
     * @param blur Graph blur amount in `0..100`; `0` disables the stage.
     * @param shortEdge Short edge of the frame being rendered.
     * @param maskTextureId Uploaded subject alpha; `0` disables the stage.
     * @param maxRadiusPx Upper bound for the bake halo, or [Int.MAX_VALUE] for a full frame.
     */
    fun params(
        blur: Int,
        shortEdge: Int,
        maskTextureId: Int,
        localSpace: Boolean,
        uvMatrix: FloatArray,
        warpTextureId: Int = 0,
        warpScale: Float = 1.0f,
        warpOffset: Float = 0.0f,
        maxRadiusPx: Int = Int.MAX_VALUE
    ): SubjectBokehDrawParams? {
        if (blur <= 0 || maskTextureId == 0) return null
        val radius = SubjectBokehMath.radiusPixels(
            blur = blur,
            shortEdge = shortEdge,
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
