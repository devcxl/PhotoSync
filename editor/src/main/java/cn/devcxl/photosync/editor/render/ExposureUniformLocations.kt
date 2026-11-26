package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

/**
 * Cached GLSL uniform locations of the Pass B program.
 *
 * Locations are resolved once after linking and then read on every draw; keeping them in one holder
 * shrinks `ExposureGlProgram` to the draw logic and makes a forgotten `glGetUniformLocation`
 * impossible when a uniform is added.
 */
internal class ExposureUniformLocations {

    var uTextureLoc: Int = -1
    var uCompareOriginalLoc: Int = -1
    var uScaleLoc: Int = -1
    var uOffsetLoc: Int = -1
    var uIsLinearInputLoc: Int = -1
    var uBaseLookLoc: Int = -1
    var uAutoBrightGainLoc: Int = -1
    var uToneActiveLoc: Int = -1
    var uHasWbLoc: Int = -1
    var uWbLoc: Int = -1
    var uExposureEvLoc: Int = -1
    var uBrightnessLoc: Int = -1
    var uContrastLoc: Int = -1
    var uGlowLoc: Int = -1
    var uHighlightsLoc: Int = -1
    var uShadowsLoc: Int = -1
    var uWhitesLoc: Int = -1
    var uBlacksLoc: Int = -1
    var uCurveLutLoc: Int = -1
    var uTexelSizeLoc: Int = -1
    var uPassB2ActiveLoc: Int = -1
    var uColorActiveLoc: Int = -1
    var uCurveActiveLoc: Int = -1
    var uSaturationLoc: Int = -1
    var uVibranceLoc: Int = -1
    var uFadeLoc: Int = -1
    var uDefringeLoc: Int = -1
    var uHslActiveLoc: Int = -1
    var uHslBandsLoc: Int = -1
    var uSplitActiveLoc: Int = -1
    var uSplitHiAbLoc: Int = -1
    var uSplitShAbLoc: Int = -1
    var uSplitBalanceLoc: Int = -1
    var uDitherAmpLoc: Int = -1
    var uSubjectMaskLoc: Int = -1
    var uSubjectGradeActiveLoc: Int = -1
    var uSubjectLocalLoc: Int = -1
    var uSubjectWarpLoc: Int = -1
    var uHasSubjectWarpLoc: Int = -1
    var uSubjectUvMatrixLoc: Int = -1
    var uSubjectWarpScaleLoc: Int = -1
    var uSubjectWarpOffsetLoc: Int = -1
    var uSubjectFgGradeLoc: Int = -1
    var uSubjectBgGradeLoc: Int = -1
    var uCloudLayerLoc: Int = -1
    var uCloudMaskLoc: Int = -1
    var uCloudActiveLoc: Int = -1
    var uCloudHasMaskLoc: Int = -1
    var uCloudOpacityLoc: Int = -1
    var uCloudUvScaleLoc: Int = -1
    var uCloudUvOffsetLoc: Int = -1
    var uSkyRelightActiveLoc: Int = -1
    var uSkyMaskLoc: Int = -1
    var uSkyUvMatrixLoc: Int = -1
    var uSkyWarpLoc: Int = -1
    var uHasSkyWarpLoc: Int = -1
    var uSkyWarpScaleLoc: Int = -1
    var uSkyWarpOffsetLoc: Int = -1
    var uSkyTileUvLoc: Int = -1
    var uSkyPixelScaleLoc: Int = -1
    var uSkyParamsLoc: Int = -1
    var uSkyRelightParamsLoc: Int = -1
    var uSkyRelightDeltaLLoc: Int = -1

    /** Resolves every location of [programId]; missing names stay `-1` and are ignored by GL. */
    fun resolve(programId: Int) {
        resolveInputAndTone(programId)
        resolveColor(programId)
        resolveSubjectAndCloud(programId)
        resolveSky(programId)
    }

    private fun resolveInputAndTone(programId: Int) {
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

    private fun resolveColor(programId: Int) {
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
    }

    private fun resolveSubjectAndCloud(programId: Int) {
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

    private fun resolveSky(programId: Int) {
        uSkyRelightActiveLoc = GLES20.glGetUniformLocation(programId, "uSkyRelightActive")
        uSkyMaskLoc = GLES20.glGetUniformLocation(programId, "uSkyMaskTex")
        uSkyUvMatrixLoc = GLES20.glGetUniformLocation(programId, "uSkyUvMatrix")
        uSkyWarpLoc = GLES20.glGetUniformLocation(programId, "uSkyWarpTex")
        uHasSkyWarpLoc = GLES20.glGetUniformLocation(programId, "uHasSkyWarpTex")
        uSkyWarpScaleLoc = GLES20.glGetUniformLocation(programId, "uSkyWarpScale")
        uSkyWarpOffsetLoc = GLES20.glGetUniformLocation(programId, "uSkyWarpOffset")
        uSkyTileUvLoc = GLES20.glGetUniformLocation(programId, "uSkyTileUv")
        uSkyPixelScaleLoc = GLES20.glGetUniformLocation(programId, "uSkyPixelScale")
        uSkyParamsLoc = GLES20.glGetUniformLocation(programId, "uSkyParams")
        uSkyRelightParamsLoc = GLES20.glGetUniformLocation(programId, "uSkyRelightParams")
        uSkyRelightDeltaLLoc = GLES20.glGetUniformLocation(programId, "uSkyRelightDeltaL")
    }
}
