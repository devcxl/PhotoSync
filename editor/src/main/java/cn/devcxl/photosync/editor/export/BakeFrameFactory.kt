package cn.devcxl.photosync.editor.export

import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.SourceSliceRect
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * Compiles one [EditGraph] into the graph-constant uniforms of a bake frame.
 *
 * Only the band geometry, the source texture and its slice rectangle change per column band; every
 * other uniform is identical for the whole export, which is what keeps the preview and the bake in
 * lock-step.
 */
internal object BakeFrameFactory {

    /**
     * @param graph Edit graph being baked.
     * @param source Staged source textures and their color metadata.
     * @param outputWidth Cropped frame width in pixels.
     * @param outputHeight Cropped frame height in pixels.
     */
    fun create(
        graph: EditGraph,
        source: BakeSourceTextures,
        outputWidth: Int,
        outputHeight: Int,
        maxWarpDisplacementUv: Float = 0.0f
    ): BakeFrame {
        val aspect = source.width.toFloat() / source.height.toFloat()
        val outputAspect = outputWidth.toFloat() / outputHeight.toFloat()
        val aberration = graph.effects.ca
        return BakeFrame(
            sourceTextureId = source.fullTexture,
            srcSliceRect = SourceSliceRect.FULL,
            band = ColumnBandTiling.fullFrame(outputWidth, outputHeight),
            srcAspect = aspect,
            isLinear = source.isLinear,
            autoBrightGain = source.autoBrightGain,
            columnMajorInverseMatrix = GeometryMath.buildPassAInverseMatrix(
                graph.geometry,
                aspect
            ).toColumnMajorFloatArray(),
            baseLook = graph.baseLook,
            toneParams = ToneShaderParams.from(graph),
            colorParams = ColorGradingParams.from(graph),
            spatialParams = SpatialDetailParams.from(graph),
            effectsParams = PassDEffectsParams.from(graph, outputAspect),
            caRedK = GeometryMath.lateralCaKFromSlider(aberration.redCyan),
            caBlueK = GeometryMath.lateralCaKFromSlider(aberration.blueYellow),
            cropWidth = outputWidth,
            cropHeight = outputHeight,
            fullResolutionShortEdge = minOf(source.width, source.height),
            maxWarpDisplacementUv = maxWarpDisplacementUv,
            subjectOps = graph.subject,
            cloudLayerOpacity = cloudLayerOpacity(graph)
        )
    }

    /**
     * Effective opacity of the graph's cloud layer, or `0` when it must not be sampled.
     *
     * The digest check is what keeps a layer generated for one crop from being pasted onto a
     * different framing; a mismatch silently degrades to "no cloud layer" instead of blending
     * misaligned pixels.
     */
    fun cloudLayerOpacity(graph: EditGraph): Float {
        val layer = graph.cloudLayer ?: return 0.0f
        if (!CloudGeometryDigest.matches(layer, graph.geometry)) return 0.0f
        return layer.opacity.coerceIn(0, 100) / PERCENT_MAX
    }

    /** Inverse homography used for both Pass A and the Tier 2 slice bounds. */
    fun inverseMatrix(graph: EditGraph, source: BakeSourceTextures) =
        GeometryMath.buildPassAInverseMatrix(
            graph.geometry,
            source.width.toFloat() / source.height.toFloat()
        )

    private const val PERCENT_MAX = 100.0f
}
