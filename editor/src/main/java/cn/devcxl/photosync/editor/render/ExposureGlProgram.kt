package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * Uniform bundle for a single Pass B preview draw.
 */
internal data class ExposureDrawParams(
    val textureId: Int,
    val compareOriginal: Boolean,
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f,
    val isLinearInput: Boolean,
    val baseLook: BaseLook,
    val autoBrightGain: Float,
    val toneParams: ToneShaderParams = ToneShaderParams()
)

/**
 * Compiles and executes the Pass B tone / colour GLSL program.
 *
 * Two input flavours share one shader: sRGB texels (JPEG or 8-bit fallback) are decoded before any
 * tone work, while `GL_RGBA16F` RAW texels (direct or Pass A output) are already scene-linear so
 * they only receive the auto-bright gain. With [BaseLook.CAMERA_DEFAULT] the shader reproduces the
 * browse-page tone curve and vibrance, so a zero-parameter edit looks identical to the gallery
 * decode. Everything above that is the shared `ToneShaderMath` pipeline: D65-anchored Bradford
 * white balance, linear exposure / brightness / highlight trims, soft shoulder, blacks toe,
 * piecewise contrast and the first two Ambiance Glow terms.
 */
internal class ExposureGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uCompareOriginalLoc: Int = -1
    private var uScaleLoc: Int = -1
    private var uOffsetLoc: Int = -1
    private var uIsLinearInputLoc: Int = -1
    private var uBaseLookLoc: Int = -1
    private var uAutoBrightGainLoc: Int = -1
    private var uToneActiveLoc: Int = -1
    private var uHasWbLoc: Int = -1
    private var uWbLoc: Int = -1
    private var uExposureEvLoc: Int = -1
    private var uBrightnessLoc: Int = -1
    private var uContrastLoc: Int = -1
    private var uGlowLoc: Int = -1
    private var uHighlightsLoc: Int = -1
    private var uShadowsLoc: Int = -1
    private var uWhitesLoc: Int = -1
    private var uBlacksLoc: Int = -1

    private val quad = QuadMesh()

    fun init(preferEs3: Boolean = true) {
        programId = if (preferEs3) {
            GlShaderCompiler.buildProgram(
                ExposureShaderSources.VERTEX_SHADER_ES3,
                ExposureShaderSources.FRAGMENT_SHADER_ES3
            ).also { id ->
                if (id == 0) {
                    Timber.w("GLES 3.0 shader compile failed; falling back to GLES 2.0 shader")
                }
            }
        } else {
            0
        }
        if (programId == 0) {
            programId = GlShaderCompiler.buildProgram(
                ExposureShaderSources.VERTEX_SHADER_ES2,
                ExposureShaderSources.FRAGMENT_SHADER_ES2
            )
        }
        if (programId == 0) {
            Timber.e("Failed to compile both GLES 3.0 and fallback tone shaders")
            return
        }
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uCompareOriginalLoc = GLES20.glGetUniformLocation(programId, "uCompareOriginal")
        uScaleLoc = GLES20.glGetUniformLocation(programId, "uScale")
        uOffsetLoc = GLES20.glGetUniformLocation(programId, "uOffset")
        uIsLinearInputLoc = GLES20.glGetUniformLocation(programId, "uIsLinearInput")
        uBaseLookLoc = GLES20.glGetUniformLocation(programId, "uBaseLook")
        uAutoBrightGainLoc = GLES20.glGetUniformLocation(programId, "uAutoBrightGain")
        uToneActiveLoc = GLES20.glGetUniformLocation(programId, "uToneActive")
        uHasWbLoc = GLES20.glGetUniformLocation(programId, "uHasWb")
        uWbLoc = GLES20.glGetUniformLocation(programId, "uWb")
        uExposureEvLoc = GLES20.glGetUniformLocation(programId, "uExposureEv")
        uBrightnessLoc = GLES20.glGetUniformLocation(programId, "uBrightness")
        uContrastLoc = GLES20.glGetUniformLocation(programId, "uContrast")
        uGlowLoc = GLES20.glGetUniformLocation(programId, "uGlow")
        uHighlightsLoc = GLES20.glGetUniformLocation(programId, "uHighlights")
        uShadowsLoc = GLES20.glGetUniformLocation(programId, "uShadows")
        uWhitesLoc = GLES20.glGetUniformLocation(programId, "uWhites")
        uBlacksLoc = GLES20.glGetUniformLocation(programId, "uBlacks")
    }

    fun draw(params: ExposureDrawParams) {
        if (programId == 0 || params.textureId == 0) return
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uExposureEvLoc, params.toneParams.exposureEv)
        GLES20.glUniform1i(uCompareOriginalLoc, if (params.compareOriginal) 1 else 0)
        GLES20.glUniform2f(uScaleLoc, params.scaleX, params.scaleY)
        GLES20.glUniform2f(uOffsetLoc, params.offsetX, params.offsetY)
        GLES20.glUniform1i(uIsLinearInputLoc, if (params.isLinearInput) 1 else 0)
        GLES20.glUniform1i(
            uBaseLookLoc,
            if (params.baseLook == BaseLook.CAMERA_DEFAULT) 1 else 0
        )
        GLES20.glUniform1f(uAutoBrightGainLoc, params.autoBrightGain)
        applyToneUniforms(params)
        applyWhiteBalanceUniforms(params.toneParams)

        quad.draw(aPositionLoc, aTexCoordLoc)
    }

    private fun applyToneUniforms(params: ExposureDrawParams) {
        val tone = params.toneParams
        val active = !params.compareOriginal && !tone.isIdentity
        GLES20.glUniform1i(uToneActiveLoc, if (active) 1 else 0)
        GLES20.glUniform1f(uBrightnessLoc, tone.brightness)
        GLES20.glUniform1f(uContrastLoc, tone.contrast)
        GLES20.glUniform1f(uGlowLoc, tone.glow)
        GLES20.glUniform1f(uHighlightsLoc, tone.highlights)
        GLES20.glUniform1f(uShadowsLoc, tone.shadows)
        GLES20.glUniform1f(uWhitesLoc, tone.whites)
        GLES20.glUniform1f(uBlacksLoc, tone.blacks)
    }

    private fun applyWhiteBalanceUniforms(tone: ToneShaderParams) {
        GLES20.glUniform1i(uHasWbLoc, if (tone.hasWhiteBalance) 1 else 0)
        GLES20.glUniformMatrix3fv(uWbLoc, 1, false, tone.wbMatrix, 0)
    }
}
