package cn.devcxl.photosync.editor.export

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.ColumnBand
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.SourceSliceRect
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/** Every graph-derived uniform one bake band needs, plus the band geometry and source binding. */
internal data class BakeFrame(
    val sourceTextureId: Int,
    val srcSliceRect: SourceSliceRect,
    val band: ColumnBand,
    val srcAspect: Float,
    val isLinear: Boolean,
    val autoBrightGain: Float,
    val columnMajorInverseMatrix: FloatArray,
    val baseLook: BaseLook,
    val toneParams: ToneShaderParams,
    val colorParams: ColorGradingParams,
    val spatialParams: SpatialDetailParams,
    val effectsParams: PassDEffectsParams,
    val caRedK: Float,
    val caBlueK: Float,
    val cropWidth: Int,
    val cropHeight: Int,
    val fullResolutionShortEdge: Int,
    val maxWarpDisplacementUv: Float = 0.0f,
    val subjectOps: SubjectOps = SubjectOps(),
    /** Effective cloud layer opacity in `0..1`; `0` keeps the band's Pass B cloud branch closed. */
    val cloudLayerOpacity: Float = 0.0f,
    /** Sanitized P3 sky group; the decoded layer and its shared delta live in [BakePassChain]. */
    val skyOps: SkyOps = SkyOps()
)

