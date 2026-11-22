package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * Immutable snapshot of all per-frame render uniforms so the GL thread never observes a torn
 * combination of homography, output aspect ratio, CA coefficients, liquify warp, Pass B1 tone,
 * Pass B2 display color and Pass C spatial detail parameters.
 */
internal data class RenderFrameState(
    val toneParams: ToneShaderParams = ToneShaderParams(),
    val colorParams: ColorGradingParams = ColorGradingParams(),
    val spatialParams: SpatialDetailParams = SpatialDetailParams(),
    val baseLook: BaseLook = BaseLook.CAMERA_DEFAULT,
    val useFastProxy: Boolean = false,
    val geometryActive: Boolean = false,
    val geometryInverseMatrix: FloatArray = IDENTITY_MATRIX,
    val lateralCaRedK: Float = 0.0f,
    val lateralCaBlueK: Float = 0.0f,
    val outputAspectRatio: Float = 1.0f,
    val liquifyWarp: Boolean = false
) {
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
 * The display-domain source `Pass B` samples: either the uploaded source texture or the `Pass A`
 * intermediate, plus the colour encoding and aspect ratio the later passes need.
 */
internal data class PassAOutput(
    val textureId: Int,
    val isLinear: Boolean,
    val width: Int,
    val height: Int,
    val aspect: Float
)
