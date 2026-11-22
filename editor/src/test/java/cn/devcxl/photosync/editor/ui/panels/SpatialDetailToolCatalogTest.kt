package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.DetailOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.NoiseReductionOps
import cn.devcxl.photosync.editor.domain.SharpenOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Seam under test: [EditorToolCatalog] for the Pass C group — every spatial-detail tool must exist in
 * the DETAIL tab, write its own `detail` field, own a dedicated undo bucket and reset alone.
 */
class SpatialDetailToolCatalogTest {

    @Test
    fun shouldGiveEverySpatialDetailToolItsOwnUndoBucket_whenInspectingTheCatalog() {
        val keys = spatialSpecs().map { it.parameterKey }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(
            setOf(
                EditParameterKey.DETAIL_CLARITY,
                EditParameterKey.DETAIL_TEXTURE,
                EditParameterKey.DETAIL_DEHAZE,
                EditParameterKey.DETAIL_SHARPEN,
                EditParameterKey.DETAIL_NOISE_REDUCTION
            ),
            keys.toSet()
        )
    }

    @Test
    fun shouldUseDialSlidersForClarityTextureAndDehaze_whenInspectingTheCatalog() {
        for (tool in listOf(EditorTool.CLARITY, EditorTool.TEXTURE, EditorTool.DEHAZE)) {
            val spec = specFor(tool)
            assertEquals(tool.name, EditorControlStyle.DIAL, spec.controlStyle)
            val dial = checkNotNull(spec.dial) { "${tool.name} must expose a dial" }
            assertEquals(tool.name, -100.0f, dial.min, 1e-4f)
            assertEquals(tool.name, 100.0f, dial.max, 1e-4f)
            assertEquals(tool.name, 1.0f, dial.step, 1e-4f)
        }
    }

    @Test
    fun shouldUseMultiRowPanelsForSharpenAndNoiseReduction_whenInspectingTheCatalog() {
        assertEquals(EditorControlStyle.SHARPEN, specFor(EditorTool.SHARPEN).controlStyle)
        assertNull(specFor(EditorTool.SHARPEN).dial)
        assertEquals(
            EditorControlStyle.NOISE_REDUCTION,
            specFor(EditorTool.NOISE_REDUCTION).controlStyle
        )
        assertNull(specFor(EditorTool.NOISE_REDUCTION).dial)
    }

    @Test
    fun shouldStoreEachSpatialSliderInItsOwnDetailField_whenWritingValues() {
        var graph = EditGraph()
        graph = write(EditorTool.CLARITY, graph, 21.0f)
        graph = write(EditorTool.TEXTURE, graph, 22.0f)
        graph = write(EditorTool.DEHAZE, graph, -23.0f)

        assertEquals(21, graph.detail.clarity)
        assertEquals(22, graph.detail.texture)
        assertEquals(-23, graph.detail.dehaze)
        assertEquals(0, graph.detail.sharpen.amount)
        assertTrue(graph.detail.nr.isIdentity())
    }

    @Test
    fun shouldResetOnlyItsOwnSpatialField_whenTheChipIsDoubleTapped() {
        val busy = EditGraph(
            detail = DetailOps(
                clarity = 30,
                texture = -20,
                dehaze = 40,
                sharpen = SharpenOps(amount = 60, radius = 2.4f, threshold = 25, masking = 55),
                nr = NoiseReductionOps(luma = 35, chroma = 45, detail = 70)
            )
        )

        val withoutClarity = reset(EditorTool.CLARITY, busy)
        assertEquals(0, withoutClarity.detail.clarity)
        assertEquals(-20, withoutClarity.detail.texture)
        assertEquals(40, withoutClarity.detail.dehaze)

        val withoutSharpen = reset(EditorTool.SHARPEN, busy)
        assertEquals(SharpenOps(), withoutSharpen.detail.sharpen)
        assertEquals(30, withoutSharpen.detail.clarity)
        assertFalse(withoutSharpen.detail.nr.isIdentity())

        val withoutNr = reset(EditorTool.NOISE_REDUCTION, busy)
        assertEquals(NoiseReductionOps(), withoutNr.detail.nr)
        assertEquals(60, withoutNr.detail.sharpen.amount)
    }

    @Test
    fun shouldReportModifiedOnlyForItsOwnSpatialField_whenReadingChipState() {
        val graph = EditGraph(
            detail = DetailOps(
                texture = 1,
                sharpen = SharpenOps(amount = 5),
                nr = NoiseReductionOps(detail = 75)
            )
        )

        assertTrue(specFor(EditorTool.TEXTURE).isModified(graph))
        assertTrue(specFor(EditorTool.SHARPEN).isModified(graph))
        assertTrue(specFor(EditorTool.NOISE_REDUCTION).isModified(graph))
        assertFalse(specFor(EditorTool.CLARITY).isModified(graph))
        assertFalse(specFor(EditorTool.DEHAZE).isModified(graph))
    }

    @Test
    fun shouldRoundTripEverySpatialDialThroughReadAfterWrite_whenInspectingTheCatalog() {
        for (spec in spatialSpecs().filter { it.controlStyle == EditorControlStyle.DIAL }) {
            val written = spec.writeValue(EditGraph(), 37.0f)

            assertEquals(spec.tool.name, 37.0f, spec.readValue(written), 1e-4f)
            assertTrue(spec.tool.name, spec.isModified(written))
            val reset = spec.reset(written)
            assertEquals(spec.tool.name, 0.0f, spec.readValue(reset), 1e-4f)
            assertFalse(spec.tool.name, spec.isModified(reset))
        }
    }

    @Test
    fun shouldCreateTwoUndoSteps_whenSharpeningAndNoiseReductionAreDraggedInsideOneWindow() {
        val controller = EditorUndoController()
        val sharpen = specFor(EditorTool.SHARPEN)
        val noiseReduction = specFor(EditorTool.NOISE_REDUCTION)

        controller.onParameterDrag(
            controller.currentGraph.copy(
                detail = controller.currentGraph.detail.copy(sharpen = SharpenOps(amount = 40))
            ),
            sharpen.parameterKey,
            timestampMs = 1000L
        )
        val afterSharpen = controller.currentGraph
        controller.onParameterDrag(
            afterSharpen.copy(
                detail = afterSharpen.detail.copy(nr = NoiseReductionOps(luma = 20))
            ),
            noiseReduction.parameterKey,
            timestampMs = 1040L
        )

        assertEquals(2, controller.undoDepth)
        assertEquals(0, controller.undo().detail.nr.luma)
    }

    private fun specFor(tool: EditorTool): EditorToolSpec =
        EditorToolCatalog.specFor(tool) ?: error("Missing spec for $tool")

    private fun write(tool: EditorTool, graph: EditGraph, value: Float): EditGraph =
        specFor(tool).writeValue(graph, value)

    private fun reset(tool: EditorTool, graph: EditGraph): EditGraph =
        specFor(tool).reset(graph)

    private fun spatialSpecs(): List<EditorToolSpec> =
        EditorToolCatalog.toolsFor(EditorGroup.DETAIL).filter {
            it.tool == EditorTool.CLARITY ||
                it.tool == EditorTool.TEXTURE ||
                it.tool == EditorTool.DEHAZE ||
                it.tool == EditorTool.SHARPEN ||
                it.tool == EditorTool.NOISE_REDUCTION
        }
}
