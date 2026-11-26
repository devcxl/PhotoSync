package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.TileUvTransform
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
    val toneParams: ToneShaderParams = ToneShaderParams(),
    val colorParams: ColorGradingParams = ColorGradingParams(),
    val curveTextureId: Int = 0,
    val texelWidth: Int = 1,
    val texelHeight: Int = 1,
    /** Triangular dither amplitude in display units; `1/255` for 8-bit targets. */
    val ditherAmp: Float = 0.0f,
    /** True only when presenting directly to the default screen framebuffer (`FBO 0`). */
    val flipVerticalForScreen: Boolean = false,
    /** Foreground/background relative grade; `null` or neutral keeps the shader branch closed. */
    val subject: SubjectOps? = null,
    /** Refined subject alpha texture; `0` disables the relight even with a non-neutral group. */
    val subjectWeightTextureId: Int = 0,
    /** True when [subjectWeightTextureId] is already in this draw's coordinate grid (bake band). */
    val subjectLocalSpace: Boolean = false,
    /** Output-UV to pre-crop-UV matrix for a source-space mask. */
    val subjectUvMatrix: FloatArray = IDENTITY_MATRIX,
    /** `uWarpTex` to mirror the Pass A liquify/portrait displacement, or `0`. */
    val subjectWarpTextureId: Int = 0,
    val subjectWarpScale: Float = 1.0f,
    val subjectWarpOffset: Float = 0.0f,
    /** Post-Pass A cloud layer texture, or `0` when no layer may be sampled. */
    val cloudLayerTextureId: Int = 0,
    /** Optional cloud coverage texture; `0` means the layer covers the whole frame. */
    val cloudMaskTextureId: Int = 0,
    /** Effective cloud opacity in `0..1`; `0` keeps the shader branch closed. */
    val cloudOpacity: Float = 0.0f,
    /** Full-frame UV transform for [cloudLayerTextureId]; identity outside a bake band. */
    val cloudUvTransform: TileUvTransform = TileUvTransform.IDENTITY
) {

    companion object {
        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    }
}

/**
 * Compiles and executes the full Pass B tone & display-color GLSL program (Pass B1 + Pass B2).
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

    private var uCurveLutLoc: Int = -1
    private var uTexelSizeLoc: Int = -1
    private var uPassB2ActiveLoc: Int = -1
    private var uColorActiveLoc: Int = -1
    private var uCurveActiveLoc: Int = -1
    private var uSaturationLoc: Int = -1
    private var uVibranceLoc: Int = -1
    private var uFadeLoc: Int = -1
    private var uDefringeLoc: Int = -1
    private var uHslActiveLoc: Int = -1
    private var uHslBandsLoc: Int = -1
    private var uSplitActiveLoc: Int = -1
    private var uSplitHiAbLoc: Int = -1
    private var uSplitShAbLoc: Int = -1
    private var uSplitBalanceLoc: Int = -1
    private var uDitherAmpLoc: Int = -1
    private var uSubjectMaskLoc: Int = -1
    private var uSubjectGradeActiveLoc: Int = -1
    private var uSubjectLocalLoc: Int = -1
    private var uSubjectWarpLoc: Int = -1
    private var uHasSubjectWarpLoc: Int = -1
    private var uSubjectUvMatrixLoc: Int = -1
    private var uSubjectWarpScaleLoc: Int = -1
    private var uSubjectWarpOffsetLoc: Int = -1
    private var uSubjectFgGradeLoc: Int = -1
    private var uSubjectBgGradeLoc: Int = -1
    private var uCloudLayerLoc: Int = -1
    private var uCloudMaskLoc: Int = -1
    private var uCloudActiveLoc: Int = -1
    private var uCloudHasMaskLoc: Int = -1
    private var uCloudOpacityLoc: Int = -1
    private var uCloudUvScaleLoc: Int = -1
    private var uCloudUvOffsetLoc: Int = -1

    private val quad = QuadMesh()

    /**
     * True only while the full ES 3 Pass B program (BaseLook, white balance, tone and display
     * color) is linked.
     *
     * The ES 2 fallbacks drop part of that chain, so an offscreen render that must reproduce the
     * canvas pixel-for-pixel has to treat `false` as "this context cannot run Pass B".
     */
    var isFullQuality: Boolean = false
        private set

    fun init(preferEs3: Boolean = true) {
        isFullQuality = false
        programId = if (preferEs3) {
            GlShaderCompiler.buildProgram(
                ExposureShaderSources.VERTEX_SHADER_ES3,
                ExposureShaderSources.FRAGMENT_SHADER_ES3
            ).also { id ->
                if (id == 0) {
                    Timber.w("GLES 3.0 shader compile failed; falling back to GLES 2.0 shader")
                }
                isFullQuality = id != 0
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
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            Timber.e(
                "Full Pass B shaders failed on renderer=%s version=%s; falling back to Tier 3 minimal shader",
                renderer,
                version
            )
            programId = GlShaderCompiler.buildProgram(
                ExposureShaderSources.VERTEX_SHADER_ES2,
                ExposureShaderSources.FRAGMENT_SHADER_MINIMAL_ES2
            )
        }
        if (programId == 0) {
            Timber.e("Failed to compile even Tier 3 minimal fallback shader")
            return
        }
        resolveBaseAndToneLocations()
        resolveColorLocations()
    }

    fun draw(params: ExposureDrawParams) {
        if (programId == 0 || params.textureId == 0) return
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1i(uCompareOriginalLoc, if (params.compareOriginal) 1 else 0)
        GLES20.glUniform2f(uScaleLoc, params.scaleX, params.scaleY)
        GLES20.glUniform2f(uOffsetLoc, params.offsetX, params.offsetY)
        GLES20.glUniform1i(uIsLinearInputLoc, if (params.isLinearInput) 1 else 0)
        GLES20.glUniform1i(
            uBaseLookLoc,
            if (params.baseLook == BaseLook.CAMERA_DEFAULT) 1 else 0
        )
        GLES20.glUniform1f(uAutoBrightGainLoc, params.autoBrightGain)
        GLES20.glUniform1f(uDitherAmpLoc, params.ditherAmp)
        applyToneUniforms(params)
        applyColorUniforms(params)
        applySubjectUniforms(params)
        applyCloudUniforms(params)

        val orientation = if (params.flipVerticalForScreen) {
            QuadOrientation.SCREEN
        } else {
            QuadOrientation.OFFSCREEN
        }
        quad.draw(aPositionLoc, aTexCoordLoc, orientation)
    }

    /** Releases the compiled shader program when tearing down an EGL context. */
    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun resolveBaseAndToneLocations() {
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
        uDitherAmpLoc = GLES20.glGetUniformLocation(programId, "uDitherAmp")
    }

    private fun resolveColorLocations() {
        uCurveLutLoc = GLES20.glGetUniformLocation(programId, "uCurveLut")
        uTexelSizeLoc = GLES20.glGetUniformLocation(programId, "uTexelSize")
        uPassB2ActiveLoc = GLES20.glGetUniformLocation(programId, "uPassB2Active")
        uColorActiveLoc = GLES20.glGetUniformLocation(programId, "uColorActive")
        uCurveActiveLoc = GLES20.glGetUniformLocation(programId, "uCurveActive")
        uSaturationLoc = GLES20.glGetUniformLocation(programId, "uSaturation")
        uVibranceLoc = GLES20.glGetUniformLocation(programId, "uVibrance")
        uFadeLoc = GLES20.glGetUniformLocation(programId, "uFade")
        uDefringeLoc = GLES20.glGetUniformLocation(programId, "uDefringe")
        uHslActiveLoc = GLES20.glGetUniformLocation(programId, "uHslActive")
        uHslBandsLoc = GLES20.glGetUniformLocation(programId, "uHslBands")
        uSplitActiveLoc = GLES20.glGetUniformLocation(programId, "uSplitActive")
        uSplitHiAbLoc = GLES20.glGetUniformLocation(programId, "uSplitHiAb")
        uSplitShAbLoc = GLES20.glGetUniformLocation(programId, "uSplitShAb")
        uSplitBalanceLoc = GLES20.glGetUniformLocation(programId, "uSplitBalance")
        uSubjectMaskLoc = GLES20.glGetUniformLocation(programId, "uSubjectMaskTex")
        uSubjectGradeActiveLoc = GLES20.glGetUniformLocation(programId, "uSubjectGradeActive")
        uSubjectLocalLoc = GLES20.glGetUniformLocation(programId, "uSubjectLocal")
        uSubjectWarpLoc = GLES20.glGetUniformLocation(programId, "uSubjectWarpTex")
        uHasSubjectWarpLoc = GLES20.glGetUniformLocation(programId, "uHasSubjectWarpTex")
        uSubjectUvMatrixLoc = GLES20.glGetUniformLocation(programId, "uSubjectUvMatrix")
        uSubjectWarpScaleLoc = GLES20.glGetUniformLocation(programId, "uSubjectWarpScale")
        uSubjectWarpOffsetLoc = GLES20.glGetUniformLocation(programId, "uSubjectWarpOffset")
        uSubjectFgGradeLoc = GLES20.glGetUniformLocation(programId, "uSubjectFgGrade")
        uSubjectBgGradeLoc = GLES20.glGetUniformLocation(programId, "uSubjectBgGrade")
        uCloudLayerLoc = GLES20.glGetUniformLocation(programId, "uCloudLayerTex")
        uCloudMaskLoc = GLES20.glGetUniformLocation(programId, "uCloudMaskTex")
        uCloudActiveLoc = GLES20.glGetUniformLocation(programId, "uCloudActive")
        uCloudHasMaskLoc = GLES20.glGetUniformLocation(programId, "uCloudHasMask")
        uCloudOpacityLoc = GLES20.glGetUniformLocation(programId, "uCloudOpacity")
        uCloudUvScaleLoc = GLES20.glGetUniformLocation(programId, "uCloudUvScale")
        uCloudUvOffsetLoc = GLES20.glGetUniformLocation(programId, "uCloudUvOffset")
    }

    /**
     * Binds the Post-Pass A cloud layer, or closes the shader branch completely.
     *
     * The branch stays closed when the layer is empty, fully transparent, or the user is comparing
     * against the camera original — Before/After must always show the untouched frame.
     */
    private fun applyCloudUniforms(params: ExposureDrawParams) {
        val active = params.cloudLayerTextureId != 0 &&
            params.cloudOpacity > 0.0f &&
            !params.compareOriginal
        GLES20.glUniform1i(uCloudActiveLoc, if (active) 1 else 0)
        GLES20.glUniform1f(uCloudOpacityLoc, params.cloudOpacity.coerceIn(0.0f, 1.0f))
        GLES20.glUniform2f(uCloudUvScaleLoc, params.cloudUvTransform.scaleX, params.cloudUvTransform.scaleY)
        GLES20.glUniform2f(
            uCloudUvOffsetLoc,
            params.cloudUvTransform.offsetX,
            params.cloudUvTransform.offsetY
        )
        val hasMask = active && params.cloudMaskTextureId != 0
        GLES20.glUniform1i(uCloudHasMaskLoc, if (hasMask) 1 else 0)
        if (!active) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.cloudLayerTextureId)
        GLES20.glUniform1i(uCloudLayerLoc, CLOUD_LAYER_UNIT)
        if (hasMask) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE5)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.cloudMaskTextureId)
            GLES20.glUniform1i(uCloudMaskLoc, CLOUD_MASK_UNIT)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun applySubjectUniforms(params: ExposureDrawParams) {
        val subject = params.subject
        val active = subject != null &&
            subject.hasGradingAdjustments() &&
            params.subjectWeightTextureId != 0 &&
            !params.compareOriginal
        GLES20.glUniform1i(uSubjectGradeActiveLoc, if (active) 1 else 0)
        GLES20.glUniform1i(uSubjectLocalLoc, if (params.subjectLocalSpace) 1 else 0)
        if (active && subject != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.subjectWeightTextureId)
            GLES20.glUniform1i(uSubjectMaskLoc, 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.subjectWarpTextureId)
            GLES20.glUniform1i(uSubjectWarpLoc, 3)
            GLES20.glUniform1i(
                uHasSubjectWarpLoc,
                if (params.subjectWarpTextureId != 0) 1 else 0
            )
            GLES20.glUniformMatrix3fv(uSubjectUvMatrixLoc, 1, false, params.subjectUvMatrix, 0)
            GLES20.glUniform2f(
                uSubjectWarpScaleLoc,
                params.subjectWarpScale,
                params.subjectWarpScale
            )
            GLES20.glUniform2f(
                uSubjectWarpOffsetLoc,
                params.subjectWarpOffset,
                params.subjectWarpOffset
            )
            GLES20.glUniform3f(
                uSubjectFgGradeLoc,
                subject.fgExposure.toGrade(),
                subject.fgTemp.toGrade(),
                subject.fgSat.toGrade()
            )
            GLES20.glUniform3f(
                uSubjectBgGradeLoc,
                subject.bgExposure.toGrade(),
                subject.bgTemp.toGrade(),
                subject.bgSat.toGrade()
            )
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
    }

    private fun Int.toGrade(): Float = this / 100.0f

    private fun applyToneUniforms(params: ExposureDrawParams) {
        val tone = params.toneParams
        val active = !params.compareOriginal && !tone.isIdentity
        GLES20.glUniform1i(uToneActiveLoc, if (active) 1 else 0)
        GLES20.glUniform1f(uExposureEvLoc, tone.exposureEv)
        GLES20.glUniform1f(uBrightnessLoc, tone.brightness)
        GLES20.glUniform1f(uContrastLoc, tone.contrast)
        GLES20.glUniform1f(uGlowLoc, tone.glow)
        GLES20.glUniform1f(uHighlightsLoc, tone.highlights)
        GLES20.glUniform1f(uShadowsLoc, tone.shadows)
        GLES20.glUniform1f(uWhitesLoc, tone.whites)
        GLES20.glUniform1f(uBlacksLoc, tone.blacks)
        GLES20.glUniform1i(uHasWbLoc, if (tone.hasWhiteBalance) 1 else 0)
        GLES20.glUniformMatrix3fv(uWbLoc, 1, false, tone.wbMatrix, 0)
    }

    private fun applyColorUniforms(params: ExposureDrawParams) {
        val color = params.colorParams
        val active = !params.compareOriginal && !color.isIdentity
        GLES20.glUniform1i(uPassB2ActiveLoc, if (active) 1 else 0)
        val curveActive = active && color.hasCurve && params.curveTextureId != 0
        GLES20.glUniform1i(uCurveActiveLoc, if (curveActive) 1 else 0)
        if (curveActive) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.curveTextureId)
            GLES20.glUniform1i(uCurveLutLoc, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        GLES20.glUniform1i(uColorActiveLoc, if (active && color.needsOklabStage) 1 else 0)
        GLES20.glUniform2f(
            uTexelSizeLoc,
            1.0f / params.texelWidth.coerceAtLeast(1).toFloat(),
            1.0f / params.texelHeight.coerceAtLeast(1).toFloat()
        )
        GLES20.glUniform1f(uSaturationLoc, color.saturation)
        GLES20.glUniform1f(uVibranceLoc, color.vibrance)
        GLES20.glUniform1f(uFadeLoc, color.fade)
        GLES20.glUniform1f(uDefringeLoc, color.defringe)
        GLES20.glUniform1i(uHslActiveLoc, if (color.hasHsl) 1 else 0)
        GLES20.glUniform3fv(uHslBandsLoc, ColorGradingParams.HSL_BAND_COUNT, color.hslBandsCopy(), 0)
        GLES20.glUniform1i(uSplitActiveLoc, if (color.hasSplit) 1 else 0)
        GLES20.glUniform2f(uSplitHiAbLoc, color.splitHiA, color.splitHiB)
        GLES20.glUniform2f(uSplitShAbLoc, color.splitShA, color.splitShB)
        GLES20.glUniform1f(uSplitBalanceLoc, color.splitBalance)
    }

    private companion object {
        /** Texture unit of the cloud layer image; 0-3 are used by source, curve and subject. */
        const val CLOUD_LAYER_UNIT = 4

        /** Texture unit of the optional cloud coverage mask. */
        const val CLOUD_MASK_UNIT = 5
    }
}
