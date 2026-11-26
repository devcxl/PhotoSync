package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

import cn.devcxl.photosync.editor.domain.SkyRelightMath

/**
 * Binds the Pass B foreground-relight uniforms and the shared sky mask / warp textures.
 *
 * Kept out of [ExposureGlProgram] so the program stays close to the 300-line ceiling and every sky
 * uniform has one owner. Mask unit 6, warp unit 3, and the Pre-pass OKLab delta as one `vec3` plus
 * the clamped lightness scalar.
 */
internal class ExposureSkyBinder(private val uniforms: ExposureUniformLocations) {

    /** Binds [ExposureDrawParams.sky]; a neutral group closes the shader branch completely. */
    fun bind(params: ExposureDrawParams) {
        val sky = params.sky
        val active = sky != null && sky.isRelightActive && !params.compareOriginal
        GLES20.glUniform1i(uniforms.uSkyRelightActiveLoc, if (active) 1 else 0)
        if (sky == null) {
            bindNeutral()
            return
        }
        bindMaskMapping(sky)
        bindRelightDelta(sky)
        if (!active) return
        bindMaskTextures(sky)
    }

    private fun bindNeutral() {
        GLES20.glUniform4f(uniforms.uSkyParamsLoc, 0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glUniform4f(uniforms.uSkyRelightParamsLoc, 0.0f, 0.0f, 0.0f, 0.0f)
        GLES20.glUniform1f(uniforms.uSkyRelightDeltaLLoc, 0.0f)
    }

    private fun bindMaskMapping(sky: SkyDrawParams) {
        GLES20.glUniformMatrix3fv(uniforms.uSkyUvMatrixLoc, 1, false, sky.uvMatrix, 0)
        GLES20.glUniform4fv(uniforms.uSkyTileUvLoc, 1, sky.tileUv.toVec4(), 0)
        GLES20.glUniform2f(uniforms.uSkyPixelScaleLoc, sky.pixelScaleX, sky.pixelScaleY)
        GLES20.glUniform4f(
            uniforms.uSkyParamsLoc,
            sky.blend.horizonOffset,
            sky.blend.featherFraction,
            sky.blend.edgeShift.toFloat(),
            if (sky.maskLocalSpace) 1.0f else 0.0f
        )
    }

    private fun bindRelightDelta(sky: SkyDrawParams) {
        val delta = SkyRelightMath.deltaRgb(sky.stats.deltaA, sky.stats.deltaB)
        GLES20.glUniform4f(
            uniforms.uSkyRelightParamsLoc,
            delta[0],
            delta[1],
            delta[2],
            sky.relight.coerceIn(0, 100) / 100.0f
        )
        GLES20.glUniform1f(
            uniforms.uSkyRelightDeltaLLoc,
            sky.stats.deltaL.coerceIn(-SkyRelightMath.MAX_LUMA_DELTA, SkyRelightMath.MAX_LUMA_DELTA)
        )
    }

    private fun bindMaskTextures(sky: SkyDrawParams) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sky.maskTextureId)
        GLES20.glUniform1i(uniforms.uSkyMaskLoc, SKY_MASK_UNIT)
        val hasWarp = sky.warpTextureId != 0
        GLES20.glUniform1i(uniforms.uHasSkyWarpLoc, if (hasWarp) 1 else 0)
        if (hasWarp) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sky.warpTextureId)
            GLES20.glUniform1i(uniforms.uSkyWarpLoc, SKY_WARP_UNIT)
            GLES20.glUniform2f(uniforms.uSkyWarpScaleLoc, sky.warpScale, sky.warpScale)
            GLES20.glUniform2f(uniforms.uSkyWarpOffsetLoc, sky.warpOffset, sky.warpOffset)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private companion object {
        const val SKY_MASK_UNIT = 6
        const val SKY_WARP_UNIT = 3
    }
}
