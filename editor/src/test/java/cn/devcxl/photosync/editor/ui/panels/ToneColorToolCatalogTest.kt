package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Seam under test: [EditorToolCatalog] — the drawer contract that turns every tone and white
 * balance slider into its own chip with its own undo bucket and its own edit-graph field.
 */
class ToneColorToolCatalogTest {

    @Test
    fun shouldOfferEveryToneSliderInPhotographicOrder_whenToneGroupIsSelected() {
        val tools = EditorToolCatalog.toolsFor(EditorGroup.TONE).map { it.tool }

        assertEquals(
            listOf(
                EditorTool.EXPOSURE,
                EditorTool.BRIGHTNESS,
                EditorTool.CONTRAST,
                EditorTool.HIGHLIGHTS,
                EditorTool.SHADOWS,
                EditorTool.WHITES,
                EditorTool.BLACKS,
                EditorTool.GLOW
            ),
            tools
        )
    }

    @Test
    fun shouldOfferColorToolsInOrder_whenColourGroupIsSelected() {
        val tools = EditorToolCatalog.toolsFor(EditorGroup.COLOR).map { it.tool }

        assertEquals(
            listOf(
                EditorTool.TEMPERATURE,
                EditorTool.TINT,
                EditorTool.SATURATION,
                EditorTool.VIBRANCE,
                EditorTool.CURVE,
                EditorTool.HSL,
                EditorTool.SPLIT_TONING,
                EditorTool.FADE
            ),
            tools
        )
        assertEquals(EditorTool.TEMPERATURE, EditorToolCatalog.defaultToolFor(EditorGroup.COLOR))
    }

    @Test
    fun shouldOfferCaAndDefringeTools_whenDetailGroupIsSelected() {
        val tools = EditorToolCatalog.toolsFor(EditorGroup.DETAIL).map { it.tool }

        assertEquals(
            listOf(
                EditorTool.DISPERSION_RED_CYAN,
                EditorTool.DISPERSION_BLUE_YELLOW,
                EditorTool.DEFRINGE
            ),
            tools
        )
    }

    @Test
    fun shouldGiveEveryToneAndColourSliderItsOwnUndoBucket_whenInspectingCatalog() {
        val keys = toneAndColourSpecs().map { it.parameterKey }

        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains(EditParameterKey.TONE_BRIGHTNESS))
        assertTrue(keys.contains(EditParameterKey.TONE_CONTRAST))
        assertTrue(keys.contains(EditParameterKey.TONE_GLOW))
        assertTrue(keys.contains(EditParameterKey.TONE_HIGHLIGHTS))
        assertTrue(keys.contains(EditParameterKey.TONE_SHADOWS))
        assertTrue(keys.contains(EditParameterKey.TONE_WHITES))
        assertTrue(keys.contains(EditParameterKey.TONE_BLACKS))
        assertTrue(keys.contains(EditParameterKey.COLOR_TEMP))
        assertTrue(keys.contains(EditParameterKey.COLOR_TINT))
        assertTrue(keys.contains(EditParameterKey.COLOR_SATURATION))
        assertTrue(keys.contains(EditParameterKey.COLOR_VIBRANCE))
        assertTrue(keys.contains(EditParameterKey.COLOR_CURVE))
        assertTrue(keys.contains(EditParameterKey.COLOR_HSL))
        assertTrue(keys.contains(EditParameterKey.COLOR_SPLIT_TONING))
        assertTrue(keys.contains(EditParameterKey.COLOR_FADE))
    }

    @Test
    fun shouldStoreEachSliderInItsOwnEditGraphField_whenWritingValues() {
        var graph = EditGraph()
        graph = write(EditorTool.BRIGHTNESS, graph, 11.0f)
        graph = write(EditorTool.CONTRAST, graph, 12.0f)
        graph = write(EditorTool.GLOW, graph, 13.0f)
        graph = write(EditorTool.HIGHLIGHTS, graph, 14.0f)
        graph = write(EditorTool.SHADOWS, graph, 15.0f)
        graph = write(EditorTool.WHITES, graph, 16.0f)
        graph = write(EditorTool.BLACKS, graph, 17.0f)
        graph = write(EditorTool.TEMPERATURE, graph, 18.0f)
        graph = write(EditorTool.TINT, graph, 19.0f)

        assertEquals(11, graph.tone.brightness)
        assertEquals(12, graph.tone.contrast)
        assertEquals(13, graph.tone.glow)
        assertEquals(14, graph.tone.highlights)
        assertEquals(15, graph.tone.shadows)
        assertEquals(16, graph.tone.whites)
        assertEquals(17, graph.tone.blacks)
        assertEquals(18, graph.color.temp)
        assertEquals(19, graph.color.tint)
    }

    @Test
    fun shouldRoundDialTicksToWholeSliderSteps_whenWritingFractionalValues() {
        val graph = write(EditorTool.SHADOWS, EditGraph(), 12.6f)

        assertEquals(13, graph.tone.shadows)
    }

    @Test
    fun shouldResetOnlyItsOwnSlider_whenTheChipIsDoubleTapped() {
        val busy = EditGraph(
            tone = ToneOps(
                brightness = 30,
                contrast = -20,
                glow = 10
            ),
            color = ColorOps(temp = 40, tint = -15)
        )

        val withoutBrightness = reset(EditorTool.BRIGHTNESS, busy)
        assertEquals(0, withoutBrightness.tone.brightness)
        assertEquals(-20, withoutBrightness.tone.contrast)
        assertEquals(40, withoutBrightness.color.temp)

        val withoutTint = reset(EditorTool.TINT, busy)
        assertEquals(0, withoutTint.color.tint)
        assertEquals(40, withoutTint.color.temp)
        assertEquals(30, withoutTint.tone.brightness)
    }

    @Test
    fun shouldReportModifiedOnlyForItsOwnSlider_whenReadingChipState() {
        val graph = EditGraph(
            tone = ToneOps(highlights = -1),
            color = ColorOps(temp = 1)
        )

        assertTrue(EditorToolCatalog.specFor(EditorTool.HIGHLIGHTS)!!.isModified(graph))
        assertTrue(EditorToolCatalog.specFor(EditorTool.TEMPERATURE)!!.isModified(graph))
        assertFalse(EditorToolCatalog.specFor(EditorTool.SHADOWS)!!.isModified(graph))
        assertFalse(EditorToolCatalog.specFor(EditorTool.CONTRAST)!!.isModified(graph))
    }

    @Test
    fun shouldRoundTripEverySliderThroughReadAfterWrite_whenInspectingCatalog() {
        for (spec in toneAndColourSpecs().filter { it.controlStyle == EditorControlStyle.DIAL }) {
            val written = spec.writeValue(EditGraph(), 37.0f)

            assertEquals(spec.tool.name, 37.0f, spec.readValue(written), 1e-4f)
            assertTrue(spec.tool.name, spec.isModified(written))
            val reset = spec.reset(written)
            assertEquals(spec.tool.name, 0.0f, spec.readValue(reset), 1e-4f)
            assertFalse(spec.tool.name, spec.isModified(reset))
        }
    }

    @Test
    fun shouldExposeDialRangesEverySliderShares_whenInspectingCatalog() {
        val exposure = EditorToolCatalog.specFor(EditorTool.EXPOSURE)!!.dial!!
        assertEquals(-5.0f, exposure.min, 1e-4f)
        assertEquals(5.0f, exposure.max, 1e-4f)

        val fade = EditorToolCatalog.specFor(EditorTool.FADE)!!.dial!!
        assertEquals(0.0f, fade.min, 1e-4f)
        assertEquals(100.0f, fade.max, 1e-4f)

        for (spec in toneAndColourSpecs().filter {
            it.controlStyle == EditorControlStyle.DIAL &&
                it.tool != EditorTool.EXPOSURE &&
                it.tool != EditorTool.FADE
        }) {
            val dial = spec.dial
            assertNotNull("${spec.tool} must have a dial", dial)
            assertEquals("${spec.tool} min", -100.0f, dial!!.min, 1e-4f)
            assertEquals("${spec.tool} max", 100.0f, dial.max, 1e-4f)
            assertEquals("${spec.tool} step", 1.0f, dial.step, 1e-4f)
            assertEquals(spec.tool.name, EditorControlStyle.DIAL, spec.controlStyle)
        }
    }

    @Test
    fun shouldCreateTwoUndoSteps_whenTwoSlidersAreDraggedInsideOneCoalescingWindow() {
        val controller = EditorUndoController()
        val brightness = EditorToolCatalog.specFor(EditorTool.BRIGHTNESS)!!
        val contrast = EditorToolCatalog.specFor(EditorTool.CONTRAST)!!

        controller.onParameterDrag(
            brightness.writeValue(controller.currentGraph, 20.0f),
            brightness.parameterKey,
            timestampMs = 1000L
        )
        controller.onParameterDrag(
            contrast.writeValue(controller.currentGraph, 15.0f),
            contrast.parameterKey,
            timestampMs = 1100L
        )

        assertEquals(2, controller.undoDepth)
        val afterUndo = controller.undo()
        assertEquals(0, afterUndo.tone.contrast)
        assertEquals(20, afterUndo.tone.brightness)
    }

    private fun write(tool: EditorTool, graph: EditGraph, value: Float): EditGraph {
        val spec = EditorToolCatalog.specFor(tool) ?: error("Missing spec for $tool")
        return spec.writeValue(graph, value)
    }

    private fun reset(tool: EditorTool, graph: EditGraph): EditGraph {
        val spec = EditorToolCatalog.specFor(tool) ?: error("Missing spec for $tool")
        return spec.reset(graph)
    }

    private fun toneAndColourSpecs(): List<EditorToolSpec> =
        EditorToolCatalog.toolsFor(EditorGroup.TONE) + EditorToolCatalog.toolsFor(EditorGroup.COLOR)
}
