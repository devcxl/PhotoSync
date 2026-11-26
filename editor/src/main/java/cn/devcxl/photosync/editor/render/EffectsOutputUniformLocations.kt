package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

/**
 * Cached GLSL uniform and attribute locations of the Pass D program.
 *
 * Resolving them once after linking keeps [EffectsOutputGlProgram] focused on draw logic and makes
 * a forgotten `glGetUniformLocation` visible as one missing assignment in this file.
 */
internal class EffectsOutputUniformLocations {

    var aPositionLoc: Int = -1
    var aTexCoordLoc: Int = -1
    var uTextureLoc: Int = -1
    var uLutLoc: Int = -1
    var uCompareOriginalLoc: Int = -1
    var uLutActiveLoc: Int = -1
    var uLutStrengthLoc: Int = -1
    var uLutSizeLoc: Int = -1
    var uGrainLoc: Int = -1
    var uVignetteLoc: Int = -1
    var uAspectLoc: Int = -1
    var uDitherAmpLoc: Int = -1
    var uScaleLoc: Int = -1
    var uOffsetLoc: Int = -1
    var uTileUvTransformLoc: Int = -1
    var uTilePixelScaleLoc: Int = -1
    var uSubjectModeLoc: Int = -1
    var uSubjectTexLoc: Int = -1
    var uSubjectColorLoc: Int = -1
    var uSubjectUvMatrixLoc: Int = -1
    var uSubjectLocalLoc: Int = -1
    var uSubjectWarpTexLoc: Int = -1
    var uHasSubjectWarpTexLoc: Int = -1
    var uSubjectWarpScaleLoc: Int = -1
    var uSubjectWarpOffsetLoc: Int = -1
    var uSubjectCheckerLoc: Int = -1
    var uSkyActiveLoc: Int = -1
    var uSkyProceduralLoc: Int = -1
    var uSkyMaskLoc: Int = -1
    var uSkyAssetLoc: Int = -1
    var uSkyUvMatrixLoc: Int = -1
    var uSkyWarpLoc: Int = -1
    var uHasSkyWarpLoc: Int = -1
    var uSkyWarpScaleLoc: Int = -1
    var uSkyWarpOffsetLoc: Int = -1
    var uSkyTileUvLoc: Int = -1
    var uSkyPixelScaleLoc: Int = -1
    var uSkyParamsLoc: Int = -1
    var uSkyRelightActiveLoc: Int = -1
    var uSkyRelightParamsLoc: Int = -1
    var uSkyRelightDeltaLLoc: Int = -1
    var uSkyZenithLoc: Int = -1
    var uSkyHorizonLoc: Int = -1
    var uSkySunColorLoc: Int = -1
    var uSkySunLoc: Int = -1
    var uSkyHazeLoc: Int = -1

    /** Resolves every location of [programId]; missing names stay `-1` and are ignored by GL. */
    fun resolve(programId: Int) {
        resolveInputs(programId)
        resolveEffects(programId)
        resolveSubject(programId)
        resolveSky(programId)
    }

    private fun resolveInputs(programId: Int) {
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uLutLoc = GLES20.glGetUniformLocation(programId, "uLutTex")
        uCompareOriginalLoc = GLES20.glGetUniformLocation(programId, "uCompareOriginal")
    }

    private fun resolveEffects(programId: Int) {
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

    private fun resolveSubject(programId: Int) {
        uSubjectModeLoc = GLES20.glGetUniformLocation(programId, "uSubjectMode")
        uSubjectTexLoc = GLES20.glGetUniformLocation(programId, "uSubjectTex")
        uSubjectColorLoc = GLES20.glGetUniformLocation(programId, "uSubjectColor")
        uSubjectUvMatrixLoc = GLES20.glGetUniformLocation(programId, "uSubjectUvMatrix")
        uSubjectLocalLoc = GLES20.glGetUniformLocation(programId, "uSubjectLocal")
        uSubjectWarpTexLoc = GLES20.glGetUniformLocation(programId, "uSubjectWarpTex")
        uHasSubjectWarpTexLoc = GLES20.glGetUniformLocation(programId, "uHasSubjectWarpTex")
        uSubjectWarpScaleLoc = GLES20.glGetUniformLocation(programId, "uSubjectWarpScale")
        uSubjectWarpOffsetLoc = GLES20.glGetUniformLocation(programId, "uSubjectWarpOffset")
        uSubjectCheckerLoc = GLES20.glGetUniformLocation(programId, "uSubjectChecker")
    }

    private fun resolveSky(programId: Int) {
        uSkyActiveLoc = GLES20.glGetUniformLocation(programId, "uSkyActive")
        uSkyProceduralLoc = GLES20.glGetUniformLocation(programId, "uSkyProcedural")
        uSkyMaskLoc = GLES20.glGetUniformLocation(programId, "uSkyMaskTex")
        uSkyAssetLoc = GLES20.glGetUniformLocation(programId, "uSkyAssetTex")
        uSkyUvMatrixLoc = GLES20.glGetUniformLocation(programId, "uSkyUvMatrix")
        uSkyWarpLoc = GLES20.glGetUniformLocation(programId, "uSkyWarpTex")
        uHasSkyWarpLoc = GLES20.glGetUniformLocation(programId, "uHasSkyWarpTex")
        uSkyWarpScaleLoc = GLES20.glGetUniformLocation(programId, "uSkyWarpScale")
        uSkyWarpOffsetLoc = GLES20.glGetUniformLocation(programId, "uSkyWarpOffset")
        uSkyTileUvLoc = GLES20.glGetUniformLocation(programId, "uSkyTileUv")
        uSkyPixelScaleLoc = GLES20.glGetUniformLocation(programId, "uSkyPixelScale")
        uSkyParamsLoc = GLES20.glGetUniformLocation(programId, "uSkyParams")
        uSkyRelightActiveLoc = GLES20.glGetUniformLocation(programId, "uSkyRelightActive")
        uSkyRelightParamsLoc = GLES20.glGetUniformLocation(programId, "uSkyRelightParams")
        uSkyRelightDeltaLLoc = GLES20.glGetUniformLocation(programId, "uSkyRelightDeltaL")
        uSkyZenithLoc = GLES20.glGetUniformLocation(programId, "uSkyZenithColor")
        uSkyHorizonLoc = GLES20.glGetUniformLocation(programId, "uSkyHorizonColor")
        uSkySunColorLoc = GLES20.glGetUniformLocation(programId, "uSkySunColor")
        uSkySunLoc = GLES20.glGetUniformLocation(programId, "uSkySun")
        uSkyHazeLoc = GLES20.glGetUniformLocation(programId, "uSkyHaze")
    }
}
