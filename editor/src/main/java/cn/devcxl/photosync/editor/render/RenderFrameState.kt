package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * Immutable snapshot of all per-frame render uniforms so the GL thread never observes a torn
 * combination of homography, output aspect ratio, CA coefficients, liquify warp, Pass B1/B2,
 * Pass C spatial detail and Pass D effects parameters.
 */
internal data class RenderFrameState(
    val toneParams: ToneShaderParams = ToneShaderParams(),
    val colorParams: ColorGradingParams = ColorGradingParams(),
    val spatialParams: SpatialDetailParams = SpatialDetailParams(),
    val effectsParams: PassDEffectsParams = PassDEffectsParams(),
    val baseLook: BaseLook = BaseLook.CAMERA_DEFAULT,
    val useFastProxy: Boolean = false,
    val geometryActive: Boolean = false,
    val geometryInverseMatrix: FloatArray = IDENTITY_MATRIX,
    val lateralCaRedK: Float = 0.0f,
    val lateralCaBlueK: Float = 0.0f,
    val outputAspectRatio: Float = 1.0f,
    val warpActive: Boolean = false,
    val subjectMode: Int = 0,
    val subjectColorRgb: Int = 0xFFFFFF,
    val subjectUvMatrix: FloatArray = IDENTITY_MATRIX,
    val subjectWarpActive: Boolean = false,
    val subjectOps: SubjectOps = SubjectOps()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenderFrameState) return false
        return toneParams == other.toneParams &&
            colorParams == other.colorParams &&
            spatialParams == other.spatialParams &&
            effectsParams == other.effectsParams &&
            baseLook == other.baseLook &&
            useFastProxy == other.useFastProxy &&
            geometryActive == other.geometryActive &&
            geometryInverseMatrix.contentEquals(other.geometryInverseMatrix) &&
            lateralCaRedK == other.lateralCaRedK &&
            lateralCaBlueK == other.lateralCaBlueK &&
            outputAspectRatio == other.outputAspectRatio &&
            warpActive == other.warpActive &&
            subjectMode == other.subjectMode &&
            subjectColorRgb == other.subjectColorRgb &&
            subjectUvMatrix.contentEquals(other.subjectUvMatrix) &&
            subjectWarpActive == other.subjectWarpActive &&
            subjectOps == other.subjectOps
    }

    override fun hashCode(): Int {
        var result = toneParams.hashCode()
        result = 31 * result + colorParams.hashCode()
        result = 31 * result + spatialParams.hashCode()
        result = 31 * result + effectsParams.hashCode()
        result = 31 * result + baseLook.hashCode()
        result = 31 * result + useFastProxy.hashCode()
        result = 31 * result + geometryActive.hashCode()
        result = 31 * result + geometryInverseMatrix.contentHashCode()
        result = 31 * result + lateralCaRedK.hashCode()
        result = 31 * result + lateralCaBlueK.hashCode()
        result = 31 * result + outputAspectRatio.hashCode()
        result = 31 * result + warpActive.hashCode()
        result = 31 * result + subjectMode
        result = 31 * result + subjectColorRgb
        result = 31 * result + subjectUvMatrix.contentHashCode()
        result = 31 * result + subjectWarpActive.hashCode()
        result = 31 * result + subjectOps.hashCode()
        return result
    }

    companion object {
        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    }
}

/** Immutable snapshot of canvas pan/zoom transforms. */
internal data class CanvasViewportTransform(
    val scale: Float = 1.0f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f
)

/**
 * Everything one preview frame needs: the active source texture, the viewport, the canvas transform,
 * full-resolution short edge (for sharpening radius scaling), monotonic Pre-pass revision, and the
 * immutable parameter snapshot.
 */
internal data class PreviewFrameRequest(
    val sourceTextureId: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val fullResolutionShortEdge: Int,
    val sourceAspect: Float,
    val isLinearInput: Boolean,
    val autoBrightGain: Float,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val canvas: CanvasViewportTransform,
    val state: RenderFrameState,
    val compareOriginal: Boolean,
    val graphRevision: Int,
    /** Uploaded source-space subject composite, or `0` when the stage is inactive. */
    val subjectTextureId: Int = 0,
    /** Uploaded refined subject alpha for Pass B relight and the terminal Pass C bokeh. */
    val subjectWeightTextureId: Int = 0,
    /** `uWarpTex` name and decode constants used to align the source-space subject alpha. */
    val subjectWarpTextureId: Int = 0,
    val subjectWarpScale: Float = 1.0f,
    val subjectWarpOffset: Float = 0.0f
)
