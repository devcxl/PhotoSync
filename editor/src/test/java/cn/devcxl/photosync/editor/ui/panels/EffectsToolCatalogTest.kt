package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EffectsOps
import cn.devcxl.photosync.editor.domain.GrainOps
import cn.devcxl.photosync.editor.domain.LutRef
import cn.devcxl.photosync.editor.domain.VignetteOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Seam under test: the Pass D catalog entries — the LUT, Grain and Vignette tools must appear in the
 * right category with dedicated panels and their own undo buckets.
 */
class EffectsToolCatalogTest {

    @Test
    fun shouldGiveEachEffectToolItsOwnPanelAndUndoBucket_whenInspectingCatalog() {
        val lut = specFor(EditorTool.LUT)
        val grain = specFor(EditorTool.GRAIN)
        val vignette = specFor(EditorTool.VIGNETTE)

        assertEquals(EditorGroup.COLOR, lut.group)
        assertEquals(EditorControlStyle.LUT, lut.controlStyle)
        assertEquals(EditParameterKey.COLOR_LUT, lut.parameterKey)

        assertEquals(EditorGroup.DETAIL, grain.group)
        assertEquals(EditorControlStyle.GRAIN, grain.controlStyle)
        assertEquals(EditParameterKey.DETAIL_GRAIN, grain.parameterKey)

        assertEquals(EditorGroup.DETAIL, vignette.group)
        assertEquals(EditorControlStyle.VIGNETTE, vignette.controlStyle)
        assertEquals(EditParameterKey.DETAIL_VIGNETTE, vignette.parameterKey)

        val effectKeys = listOf(lut, grain, vignette).map { it.parameterKey }
        assertEquals(effectKeys.size, effectKeys.toSet().size)
    }

    @Test
    fun shouldOnlyFlagTheToolThatOwnsTheEffect_whenReadingChipState() {
        val busy = EditGraph(
            color = ColorOps(lut = LutRef("kodak_2383", 60)),
            effects = EffectsOps(
                grain = GrainOps(amount = 20),
                vignette = VignetteOps(amount = -30)
            )
        )

        assertTrue(specFor(EditorTool.LUT).isModified(busy))
        assertTrue(specFor(EditorTool.GRAIN).isModified(busy))
        assertTrue(specFor(EditorTool.VIGNETTE).isModified(busy))

        val lutOnly = busy.copy(effects = EffectsOps())
        assertTrue(specFor(EditorTool.LUT).isModified(lutOnly))
        assertFalse(specFor(EditorTool.GRAIN).isModified(lutOnly))
        assertFalse(specFor(EditorTool.VIGNETTE).isModified(lutOnly))
    }

    @Test
    fun shouldResetOnlyItsOwnEffect_whenTheChipIsDoubleTapped() {
        val busy = EditGraph(
            color = ColorOps(lut = LutRef("portra", 40)),
            effects = EffectsOps(
                grain = GrainOps(amount = 25, size = 40),
                vignette = VignetteOps(amount = -45, feather = 70)
            )
        )

        val withoutLut = specFor(EditorTool.LUT).reset(busy)
        assertNull(withoutLut.color.lut)
        assertEquals(25, withoutLut.effects.grain.amount)

        val withoutGrain = specFor(EditorTool.GRAIN).reset(busy)
        assertEquals(GrainOps(), withoutGrain.effects.grain)
        assertEquals(-45, withoutGrain.effects.vignette.amount)
        assertEquals(LutRef("portra", 40), withoutGrain.color.lut)

        val withoutVignette = specFor(EditorTool.VIGNETTE).reset(busy)
        assertEquals(VignetteOps(), withoutVignette.effects.vignette)
        assertEquals(25, withoutVignette.effects.grain.amount)
    }

    private fun specFor(tool: EditorTool): EditorToolSpec =
        EditorToolCatalog.specFor(tool) ?: error("Missing spec for $tool")
}
