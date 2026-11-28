package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.ProceduralSky
import cn.devcxl.photosync.editor.domain.TileUvTransform

/**
 * Uniform bundle for a single Pass D draw.
 */
internal data class EffectsDrawParams(
    val textureId: Int,
    val lutTextureId: Int,
    val compareOriginal: Boolean,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float,
    val effects: PassDEffectsParams,
    val ditherAmp: Float,
    val tileUv: TileUvTransform = TileUvTransform.IDENTITY,
    val pixelScaleX: Float = 1.0f,
    val pixelScaleY: Float = 1.0f,
    /** True only when presenting directly to the default screen framebuffer (`FBO 0`). */
    val flipVerticalForScreen: Boolean = false,
    /** Optional Pass D subject composite; `null` keeps the shader's zero-cost short circuit. */
    val subject: SubjectDrawParams? = null,
    /** Optional P3 sky composite; `null` keeps the shader's zero-cost short circuit. */
    val sky: SkyDrawParams? = null
)

/**
 * Compiles and executes the Pass D effects & output quantization program.
 *
 * The program is GLES 3.0 only because tetrahedral LUT interpolation needs `texelFetch` on a
 * `sampler3D`. When the driver cannot compile or link it, the renderer records a Tier 3 fallback and
 * keeps Pass B on screen instead.
 */
internal class EffectsOutputGlProgram {

    private var programId: Int = 0
    private val uniforms = EffectsOutputUniformLocations()
    private val quad = QuadMesh()

    val isReady: Boolean
        get() = programId != 0

    /**
     * Compiles and links the Pass D program.
     *
     * A failure is reported through the return value only: the owning stage decides whether it is a
     * Tier 3 minimal fallback for its own EGL context.
     */
    fun init(): Boolean {
        programId = GlShaderCompiler.buildProgram(
            ExposureShaderSources.VERTEX_SHADER_ES3,
            EffectsOutputShaderSources.FRAGMENT_SHADER_ES3
        )
        if (programId == 0) return false
        uniforms.resolve(programId)
        return true
    }

    /** Draws [params] into the currently bound framebuffer. */
    fun draw(params: EffectsDrawParams) {
        if (programId == 0 || params.textureId == 0) return
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.textureId)
        GLES20.glUniform1i(uniforms.uTextureLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, params.lutTextureId)
        GLES20.glUniform1i(uniforms.uLutLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform1i(uniforms.uCompareOriginalLoc, if (params.compareOriginal) 1 else 0)
        GLES20.glUniform2f(uniforms.uScaleLoc, params.scaleX, params.scaleY)
        GLES20.glUniform2f(uniforms.uOffsetLoc, params.offsetX, params.offsetY)
        bindEffectUniforms(params)
        bindSubjectUniforms(params.subject)
        bindSkyUniforms(params.sky, params.compareOriginal)
        val orientation = if (params.flipVerticalForScreen) {
            QuadOrientation.SCREEN
        } else {
            QuadOrientation.OFFSCREEN
        }
        quad.draw(uniforms.aPositionLoc, uniforms.aTexCoordLoc, orientation)
    }

    /** Logs driver diagnostics before the context goes away, then drops the program name. */
    fun release() {
        if (programId != 0) {
            Timber.d("Releasing the Pass D effects program")
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun bindEffectUniforms(params: EffectsDrawParams) {
        val effects = params.effects
        val lutActive = params.lutTextureId != 0 && effects.hasLutEffects
        GLES20.glUniform1i(uniforms.uLutActiveLoc, if (lutActive) 1 else 0)
        GLES20.glUniform1f(uniforms.uLutStrengthLoc, effects.lutStrength)
        GLES20.glUniform1f(uniforms.uLutSizeLoc, effects.lutSize.toFloat())
        GLES20.glUniform4f(
            uniforms.uGrainLoc,
            effects.grainAmount,
            effects.grainSize,
            effects.grainRoughness,
            effects.grainSeed.toFloat()
        )
        GLES20.glUniform4f(
            uniforms.uVignetteLoc,
            effects.vignetteAmount,
            effects.vignetteMidpoint,
            effects.vignetteFeather,
            effects.vignetteRoundness
        )
        GLES20.glUniform1f(uniforms.uAspectLoc, effects.aspectRatio)
        GLES20.glUniform1f(uniforms.uDitherAmpLoc, params.ditherAmp)
        GLES20.glUniform4fv(uniforms.uTileUvTransformLoc, 1, params.tileUv.toVec4(), 0)
        GLES20.glUniform2f(uniforms.uTilePixelScaleLoc, params.pixelScaleX, params.pixelScaleY)
    }

    private fun bindSubjectUniforms(subject: SubjectDrawParams?) {
        if (subject == null || subject.textureId == 0 || subject.mode == 0) {
            GLES20.glUniform1i(uniforms.uSubjectModeLoc, 0)
            GLES20.glUniform1i(uniforms.uSubjectCheckerLoc, 0)
            return
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, subject.textureId)
        GLES20.glUniform1i(uniforms.uSubjectTexLoc, 2)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, subject.warpTextureId)
        GLES20.glUniform1i(uniforms.uSubjectWarpTexLoc, 3)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform1i(uniforms.uSubjectModeLoc, subject.mode)
        GLES20.glUniform3f(
            uniforms.uSubjectColorLoc,
            ((subject.solidColorRgb ushr 16) and 0xFF) / 255.0f,
            ((subject.solidColorRgb ushr 8) and 0xFF) / 255.0f,
            (subject.solidColorRgb and 0xFF) / 255.0f
        )
        GLES20.glUniformMatrix3fv(uniforms.uSubjectUvMatrixLoc, 1, false, subject.uvMatrix, 0)
        GLES20.glUniform1i(uniforms.uSubjectLocalLoc, if (subject.localSpace) 1 else 0)
        GLES20.glUniform1i(
            uniforms.uHasSubjectWarpTexLoc,
            if (subject.warpTextureId != 0) 1 else 0
        )
        GLES20.glUniform2f(
            uniforms.uSubjectWarpScaleLoc,
            subject.warpScale,
            subject.warpScale
        )
        GLES20.glUniform2f(
            uniforms.uSubjectWarpOffsetLoc,
            subject.warpOffset,
            subject.warpOffset
        )
        GLES20.glUniform1i(uniforms.uSubjectCheckerLoc, if (subject.checkerboard) 1 else 0)
    }

    /**
     * Binds the sky composite: mask at unit 4, replacement image at unit 5 and the shared warp grid
     * at unit 3. The branch stays closed for an inactive graph or Before/After comparison.
     */
    private fun bindSkyUniforms(sky: SkyDrawParams?, compareOriginal: Boolean) {
        val active = sky != null && sky.isCompositeActive && !compareOriginal
        GLES20.glUniform1i(uniforms.uSkyActiveLoc, if (active) 1 else 0)
        GLES20.glUniform1i(uniforms.uSkyRelightActiveLoc, 0)
        if (sky == null) return
        val procedural = sky.procedural != null
        GLES20.glUniform1i(uniforms.uSkyProceduralLoc, if (procedural) 1 else 0)
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
        bindPalette(sky)
        if (!active) return
        bindSkyTextures(sky, procedural)
    }

    private fun bindSkyTextures(sky: SkyDrawParams, procedural: Boolean) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sky.maskTextureId)
        GLES20.glUniform1i(uniforms.uSkyMaskLoc, SKY_MASK_UNIT)
        if (!procedural) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sky.assetTextureId)
            GLES20.glUniform1i(uniforms.uSkyAssetLoc, SKY_ASSET_UNIT)
        }
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

    private fun bindPalette(sky: SkyDrawParams) {
        val preset = sky.procedural
        if (preset == null) {
            GLES20.glUniform3f(uniforms.uSkyZenithLoc, 0.0f, 0.0f, 0.0f)
            GLES20.glUniform3f(uniforms.uSkyHorizonLoc, 0.0f, 0.0f, 0.0f)
            GLES20.glUniform3f(uniforms.uSkySunColorLoc, 0.0f, 0.0f, 0.0f)
            GLES20.glUniform3f(uniforms.uSkySunLoc, 0.5f, 0.5f, 0.0f)
            GLES20.glUniform1f(uniforms.uSkyHazeLoc, 0.0f)
            return
        }
        val palette = ProceduralSky.palette(preset)
        GLES20.glUniform3f(
            uniforms.uSkyZenithLoc,
            red(palette.zenithArgb),
            green(palette.zenithArgb),
            blue(palette.zenithArgb)
        )
        GLES20.glUniform3f(
            uniforms.uSkyHorizonLoc,
            red(palette.horizonArgb),
            green(palette.horizonArgb),
            blue(palette.horizonArgb)
        )
        GLES20.glUniform3f(
            uniforms.uSkySunColorLoc,
            red(palette.sunArgb),
            green(palette.sunArgb),
            blue(palette.sunArgb)
        )
        GLES20.glUniform3f(uniforms.uSkySunLoc, palette.sunX, palette.sunY, palette.sunIntensity)
        GLES20.glUniform1f(uniforms.uSkyHazeLoc, palette.haze)
    }

    private fun red(argb: Int): Float = ((argb ushr 16) and 0xFF) / 255.0f

    private fun green(argb: Int): Float = ((argb ushr 8) and 0xFF) / 255.0f

    private fun blue(argb: Int): Float = (argb and 0xFF) / 255.0f

    private companion object {
        const val SKY_MASK_UNIT = 4
        const val SKY_ASSET_UNIT = 5
        const val SKY_WARP_UNIT = 3
    }
}
