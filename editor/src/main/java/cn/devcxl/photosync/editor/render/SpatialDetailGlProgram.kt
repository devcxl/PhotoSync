package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

import cn.devcxl.photosync.editor.domain.SpatialDetailStage

/**
 * Uniform bundle of one Pass C sub-pass draw.
 */
internal data class SpatialDetailDrawParams(
    val stage: SpatialDetailStage,
    val inputTextureId: Int,
    val prepassTextureId: Int = 0,
    val skinMaskTextureId: Int = 0,
    val dehazeStrength: Float = 0.0f,
    val atmosphericLight: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f),
    val clarity: Float = 0.0f,
    val texture: Float = 0.0f,
    val nrLuma: Float = 0.0f,
    val nrChroma: Float = 0.0f,
    val nrDetail: Float = 0.5f,
    val sharpenAmount: Float = 0.0f,
    val sharpenRadiusPx: Float = 1.0f,
    val sharpenThreshold: Float = 0.0f,
    val sharpenMasking: Float = 0.0f,
    val skinSmooth: Float = 0.0f,
    val skinTexture: Float = 0.5f,
    val skinEvenTone: Float = 0.0f,
    val skinBlurScale: Float = 1.0f,
    val texelWidth: Int = 1,
    val texelHeight: Int = 1,
    val ditherAmp: Float = 0.0f,
    val guideUvScaleX: Float = 1.0f,
    val guideUvScaleY: Float = 1.0f,
    val guideUvOffsetX: Float = 0.0f,
    val guideUvOffsetY: Float = 0.0f
)

/**
 * GLES 3.0 program of `SpatialDetailChain`: one shader, one uniform block, four stages.
 */
internal class SpatialDetailGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uPrepassLoc: Int = -1
    private var uSkinMaskLoc: Int = -1
    private var uHasPrepassLoc: Int = -1
    private var uHasSkinMaskLoc: Int = -1
    private var uStageLoc: Int = -1
    private var uTexelSizeLoc: Int = -1
    private var uTileUvTransformLoc: Int = -1
    private var uNrParamsLoc: Int = -1
    private var uDehazeStrengthLoc: Int = -1
    private var uDehazeALoc: Int = -1
    private var uClarityTextureLoc: Int = -1
    private var uSharpenLoc: Int = -1
    private var uSkinParamsLoc: Int = -1
    private var uSkinBlurScaleLoc: Int = -1
    private var uDitherAmpLoc: Int = -1

    private val quad = QuadMesh()

    val isReady: Boolean
        get() = programId != 0

    /** Compiles the Pass C program; records Tier 3 minimal fallback when the driver rejects it. */
    fun init(): Boolean {
        programId = GlShaderCompiler.buildProgram(
            DetailShaderSources.VERTEX_SHADER,
            DetailShaderSources.FRAGMENT_SHADER
        )
        if (programId == 0) {
            GlCapabilities.markMinimalFallback("Pass C SpatialDetail")
            return false
        }
        resolveLocations()
        return true
    }

    /** Releases the GL program. Safe to call without a live context. */
    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    /** Rasterizes one Pass C sub-pass into the currently bound target. */
    fun draw(params: SpatialDetailDrawParams) {
        if (programId == 0 || params.inputTextureId == 0) return
        GLES20.glUseProgram(programId)
        bindTextures(params)
        GLES20.glUniform1i(uStageLoc, params.stage.ordinal + STAGE_OFFSET)
        GLES20.glUniform2f(
            uTexelSizeLoc,
            1.0f / params.texelWidth.coerceAtLeast(1).toFloat(),
            1.0f / params.texelHeight.coerceAtLeast(1).toFloat()
        )
        GLES20.glUniform4f(
            uTileUvTransformLoc,
            params.guideUvScaleX,
            params.guideUvScaleY,
            params.guideUvOffsetX,
            params.guideUvOffsetY
        )
        GLES20.glUniform3f(uNrParamsLoc, params.nrLuma, params.nrChroma, params.nrDetail)
        GLES20.glUniform1f(uDehazeStrengthLoc, params.dehazeStrength)
        GLES20.glUniform3fv(uDehazeALoc, 1, params.atmosphericLight, 0)
        GLES20.glUniform2f(uClarityTextureLoc, params.clarity, params.texture)
        GLES20.glUniform4f(
            uSharpenLoc,
            params.sharpenAmount,
            params.sharpenRadiusPx,
            params.sharpenThreshold,
            params.sharpenMasking
        )
        GLES20.glUniform3f(uSkinParamsLoc, params.skinSmooth, params.skinTexture, params.skinEvenTone)
        GLES20.glUniform1f(uSkinBlurScaleLoc, params.skinBlurScale)
        GLES20.glUniform1f(uDitherAmpLoc, params.ditherAmp)
        quad.draw(aPositionLoc, aTexCoordLoc, QuadOrientation.OFFSCREEN)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun bindTextures(params: SpatialDetailDrawParams) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.inputTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.prepassTextureId)
        GLES20.glUniform1i(uPrepassLoc, 1)
        GLES20.glUniform1i(uHasPrepassLoc, if (params.prepassTextureId != 0) 1 else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.skinMaskTextureId)
        GLES20.glUniform1i(uSkinMaskLoc, 2)
        GLES20.glUniform1i(uHasSkinMaskLoc, if (params.skinMaskTextureId != 0) 1 else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun resolveLocations() {
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uPrepassLoc = GLES20.glGetUniformLocation(programId, "uPrepassTex")
        uSkinMaskLoc = GLES20.glGetUniformLocation(programId, "uSkinMaskTex")
        uHasPrepassLoc = GLES20.glGetUniformLocation(programId, "uHasPrepass")
        uHasSkinMaskLoc = GLES20.glGetUniformLocation(programId, "uHasSkinMask")
        uStageLoc = GLES20.glGetUniformLocation(programId, "uStage")
        uTexelSizeLoc = GLES20.glGetUniformLocation(programId, "uTexelSize")
        uTileUvTransformLoc = GLES20.glGetUniformLocation(programId, "uTileUvTransform")
        uNrParamsLoc = GLES20.glGetUniformLocation(programId, "uNrParams")
        uDehazeStrengthLoc = GLES20.glGetUniformLocation(programId, "uDehazeStrength")
        uDehazeALoc = GLES20.glGetUniformLocation(programId, "uDehazeA")
        uClarityTextureLoc = GLES20.glGetUniformLocation(programId, "uClarityTexture")
        uSharpenLoc = GLES20.glGetUniformLocation(programId, "uSharpen")
        uSkinParamsLoc = GLES20.glGetUniformLocation(programId, "uSkinParams")
        uSkinBlurScaleLoc = GLES20.glGetUniformLocation(programId, "uSkinBlurScale")
        uDitherAmpLoc = GLES20.glGetUniformLocation(programId, "uDitherAmp")
    }

    private companion object {
        private const val STAGE_OFFSET = 1
    }
}
