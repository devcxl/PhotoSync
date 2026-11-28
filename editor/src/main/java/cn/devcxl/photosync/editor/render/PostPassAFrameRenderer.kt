package cn.devcxl.photosync.editor.render

import kotlin.math.roundToInt

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.DetailOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EffectsOps
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.PortraitOps
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Renders the clean **Post-Pass A / Pre-Pass B** frame the cloud edit uploads.
 *
 * This is deliberately not the canvas pixels: a cloud request must never carry the local grade, the
 * 3D LUT, grain or an existing cloud layer, because the returned layer is mounted *before* Pass B and
 * will be graded again on device. What travels is exactly what Pass B would otherwise grade —
 * geometry (crop, rotate, straighten, perspective, CA, warp) and the RAW `BaseLook`.
 *
 * The frame is capped at [MAX_EDGE_PX] because the BYOK endpoint downsizes to 2048 px anyway and a
 * phone-sized bitmap is what keeps the preview preview latency low.
 */
internal object PostPassAFrameRenderer {

    /** Long edge of the rendered frame; matches the upload budget of the cloud service. */
    const val MAX_EDGE_PX = 1536

    /**
     * Renders [graph] up to the Pass A boundary.
     *
     * @param source Decoded session source; never released or mutated.
     * @param graph Current edit graph.
     * @param warpField Composed face/body/liquify displacement, or `null`.
     * @param inpaint Repair overlay composed into a private copy, or `null`.
     * @return A new `ARGB_8888` bitmap owned by the caller, or `null` on an OOM/degenerate size.
     */
    fun render(
        source: EditorSource,
        graph: EditGraph,
        warpField: WarpDisplacementField? = null,
        inpaint: InpaintOverlay? = null,
        maxEdgePx: Int = MAX_EDGE_PX
    ): Bitmap? = try {
        when (source) {
            is EditorSource.BitmapSource -> renderBitmap(source.full, graph, warpField, inpaint, maxEdgePx)
            is EditorSource.LinearRawSource ->
                renderLinear(source.full, graph, warpField, inpaint, maxEdgePx)
        }
    } catch (oom: OutOfMemoryError) {
        null
    }

    private fun renderBitmap(
        source: Bitmap,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        inpaint: InpaintOverlay?,
        maxEdgePx: Int
    ): Bitmap? {
        val frame = inpaint?.applyToBitmapCopy(source) ?: source
        try {
            val sourceAspect = frame.width.toFloat() / frame.height.toFloat()
            val (width, height) = outputSize(graph, sourceAspect, maxEdgePx)
            val pixels = GeometryCpuResampler.resamplePixels(
                source = frame,
                inverseMatrix = GeometryMath.buildPassAInverseMatrix(graph.geometry, sourceAspect),
                outputWidth = width,
                outputHeight = height,
                caRedK = GeometryMath.lateralCaKFromSlider(graph.effects.ca.redCyan),
                caBlueK = GeometryMath.lateralCaKFromSlider(graph.effects.ca.blueYellow),
                warpField = warpField
            )
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } finally {
            if (frame !== source) frame.recycle()
        }
    }

    private fun renderLinear(
        source: LinearRawBuffer,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        inpaint: InpaintOverlay?,
        maxEdgePx: Int
    ): Bitmap? {
        val frame = inpaint?.applyToLinearCopy(source) ?: source
        val sourceAspect = frame.width.toFloat() / frame.height.toFloat()
        val (width, height) = outputSize(graph, sourceAspect, maxEdgePx)
        val resampled = GeometryCpuResampler.resampleLinear(
            source = frame,
            inverseMatrix = GeometryMath.buildPassAInverseMatrix(graph.geometry, sourceAspect),
            outputWidth = width,
            outputHeight = height,
            caRedK = GeometryMath.lateralCaKFromSlider(graph.effects.ca.redCyan),
            caBlueK = GeometryMath.lateralCaKFromSlider(graph.effects.ca.blueYellow),
            warpField = warpField
        )
        val pixels = LinearRawPreviewRenderer.renderArgb(
            source = resampled,
            graph = passABoundaryGraph(graph),
            fullResolutionShortEdge = minOf(frame.width, frame.height)
        )
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Keeps only what Pass A and the RAW `BaseLook` contribute.
     *
     * Pass B/C/D groups, the subject stage and any existing cloud layer are neutralised, so the
     * upload cannot bake the current grade into a layer that would then be graded twice.
     */
    private fun passABoundaryGraph(graph: EditGraph) = graph.copy(
        tone = ToneOps(),
        color = ColorOps(),
        detail = DetailOps(),
        effects = EffectsOps(),
        portrait = PortraitOps(),
        subject = SubjectOps(),
        cloudLayer = null
    )

    private fun outputSize(graph: EditGraph, sourceAspect: Float, maxEdgePx: Int): Pair<Int, Int> {
        val aspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
            .takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        return if (aspect >= 1.0f) {
            maxEdgePx to (maxEdgePx / aspect).roundToInt().coerceAtLeast(1)
        } else {
            (maxEdgePx * aspect).roundToInt().coerceAtLeast(1) to maxEdgePx
        }
    }
}
