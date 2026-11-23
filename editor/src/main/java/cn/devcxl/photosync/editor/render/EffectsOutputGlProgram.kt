package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.PassDEffectsParams
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
    val flipVerticalForScreen: Boolean = false
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
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uLutLoc: Int = -1
    private var uCompareOriginalLoc: Int = -1
    private var uLutActiveLoc: Int = -1
    private var uLutStrengthLoc: Int = -1
    private var uLutSizeLoc: Int = -1
    private var uGrainLoc: Int = -1
    private var uVignetteLoc: Int = -1
    private var uAspectLoc: Int = -1
    private var uDitherAmpLoc: Int = -1
    private var uScaleLoc: Int = -1
    private var uOffsetLoc: Int = -1
    private var uTileUvTransformLoc: Int = -1
    private var uTilePixelScaleLoc: Int = -1

    private val quad = QuadMesh()

    val isReady: Boolean
        get() = programId != 0

    /** Compiles and links the Pass D program. Must run on the GL thread. */
    fun init(): Boolean {
        programId = GlShaderCompiler.buildProgram(
            ExposureShaderSources.VERTEX_SHADER_ES3,
            EffectsOutputShaderSources.FRAGMENT_SHADER_ES3
        )
        if (programId == 0) {
            GlCapabilities.markMinimalFallback("Pass D effects shader")
            return false
        }
        resolveLocations()
        return true
    }

    /** Draws [params] into the currently bound framebuffer. */
    fun draw(params: EffectsDrawParams) {
        if (programId == 0 || params.textureId == 0) return
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, params.lutTextureId)
        GLES20.glUniform1i(uLutLoc, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        GLES20.glUniform1i(uCompareOriginalLoc, if (params.compareOriginal) 1 else 0)
        GLES20.glUniform2f(uScaleLoc, params.scaleX, params.scaleY)
        GLES20.glUniform2f(uOffsetLoc, params.offsetX, params.offsetY)
        bindEffectUniforms(params)
        val orientation = if (params.flipVerticalForScreen) {
            QuadOrientation.SCREEN
        } else {
            QuadOrientation.OFFSCREEN
        }
        quad.draw(aPositionLoc, aTexCoordLoc, orientation)
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
        GLES20.glUniform1i(uLutActiveLoc, if (lutActive) 1 else 0)
        GLES20.glUniform1f(uLutStrengthLoc, effects.lutStrength)
        GLES20.glUniform1f(uLutSizeLoc, effects.lutSize.toFloat())
        GLES20.glUniform4f(
            uGrainLoc,
            effects.grainAmount,
            effects.grainSize,
            effects.grainRoughness,
            effects.grainSeed.toFloat()
        )
        GLES20.glUniform4f(
            uVignetteLoc,
            effects.vignetteAmount,
            effects.vignetteMidpoint,
            effects.vignetteFeather,
            effects.vignetteRoundness
        )
        GLES20.glUniform1f(uAspectLoc, effects.aspectRatio)
        GLES20.glUniform1f(uDitherAmpLoc, params.ditherAmp)
        GLES20.glUniform4fv(uTileUvTransformLoc, 1, params.tileUv.toVec4(), 0)
        GLES20.glUniform2f(uTilePixelScaleLoc, params.pixelScaleX, params.pixelScaleY)
    }

    private fun resolveLocations() {
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uLutLoc = GLES20.glGetUniformLocation(programId, "uLutTex")
        uCompareOriginalLoc = GLES20.glGetUniformLocation(programId, "uCompareOriginal")
        uLutActiveLoc = GLES20.glGetUniformLocation(programId, "uLutActive")
        uLutStrengthLoc = GLES20.glGetUniformLocation(programId, "uLutStrength")
        uLutSizeLoc = GLES20.glGetUniformLocation(programId, "uLutSize")
        uGrainLoc = GLES20.glGetUniformLocation(programId, "uGrain")
        uVignetteLoc = GLES20.glGetUniformLocation(programId, "uVignette")
        uAspectLoc = GLES20.glGetUniformLocation(programId, "uAspect")
        uDitherAmpLoc = GLES20.glGetUniformLocation(programId, "uDitherAmp")
        uScaleLoc = GLES20.glGetUniformLocation(programId, "uScale")
        uOffsetLoc = GLES20.glGetUniformLocation(programId, "uOffset")
        uTileUvTransformLoc = GLES20.glGetUniformLocation(programId, "uTileUvTransform")
        uTilePixelScaleLoc = GLES20.glGetUniformLocation(programId, "uTilePixelScale")
    }
}
