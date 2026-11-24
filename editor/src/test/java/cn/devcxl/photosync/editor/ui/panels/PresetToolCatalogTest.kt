package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.CopyMask
import cn.devcxl.photosync.editor.domain.DetailOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EffectsOps
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.PortraitOps
import cn.devcxl.photosync.editor.domain.ReshapeOps
import cn.devcxl.photosync.editor.domain.SkinOps
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Seam under test: [EditorPresetSpecs] and [CopyMask.fromCheckboxState] — the `预设` group must be a
 * real tool group, not the "coming soon" dead end, and the copy checklist must map onto the mask.
 */
class PresetToolCatalogTest {

    @Test
    fun shouldExposeThePresetTool_whenThePresetGroupIsSelected() {
        val tools = EditorToolCatalog.toolsFor(EditorGroup.PRESET)

        assertEquals(1, tools.size)
        assertEquals(EditorTool.PRESET, tools.single().tool)
        assertEquals(EditorTool.PRESET, EditorToolCatalog.defaultToolFor(EditorGroup.PRESET))
    }

    @Test
    fun shouldUseThePresetControlStyleAndUndoKey_whenInspectingTheSpec() {
        val spec = checkNotNull(EditorToolCatalog.specFor(EditorTool.PRESET))

        assertEquals(EditorControlStyle.PRESET, spec.controlStyle)
        assertEquals(EditParameterKey.PRESET, spec.parameterKey)
        assertEquals(EditorGroup.PRESET, spec.group)
        assertNotNull(spec.labelRes)
    }

    @Test
    fun shouldReportModified_whenAnyPresetAxisDiffersFromNeutral() {
        val spec = checkNotNull(EditorToolCatalog.specFor(EditorTool.PRESET))
        val graded = EditGraph(tone = ToneOps(contrast = 12))

        assertTrue(spec.isModified(graded))
        assertFalse(spec.isModified(EditGraph()))
    }

    @Test
    fun shouldIgnoreComposition_whenDecidingModified() {
        val spec = checkNotNull(EditorToolCatalog.specFor(EditorTool.PRESET))
        val composedOnly = EditGraph(
            geometry = GeometryOps(straighten = 4.0f),
            portrait = PortraitOps(reshape = ReshapeOps(face = 20))
        )

        assertFalse(spec.isModified(composedOnly))
    }

    @Test
    fun shouldResetEveryAxis_whenTheChipIsDoubleTapped() {
        val spec = checkNotNull(EditorToolCatalog.specFor(EditorTool.PRESET))
        val busy = EditGraph(
            geometry = GeometryOps(rotate90 = 1, straighten = 3.0f),
            tone = ToneOps(exposure = 0.6f, shadows = 20),
            color = ColorOps(temp = 25),
            detail = DetailOps(clarity = 30),
            effects = EffectsOps(),
            portrait = PortraitOps(
                skin = SkinOps(smooth = 40),
                reshape = ReshapeOps(face = 15),
                body = cn.devcxl.photosync.editor.domain.BodyOps(waist = 10, waistY = 0.44f)
            )
        )

        val reset = spec.reset(busy)

        assertTrue(reset.isIdentity())
        assertEquals(0, reset.geometry.rotate90)
        assertTrue(reset.portrait.isIdentity())
    }

    @Test
    fun shouldMapDefaultChecklistToTheDefaultMask_whenAllLookBoxesAreChecked() {
        val checked = BooleanArray(9) { index -> index < 6 }

        assertEquals(CopyMask(), CopyMask.fromCheckboxState(checked))
    }

    @Test
    fun shouldIncludeEveryAxis_whenEveryBoxIsChecked() {
        val mask = CopyMask.fromCheckboxState(BooleanArray(9) { true })

        assertTrue(mask.includeBaseLook)
        assertTrue(mask.includeTone)
        assertTrue(mask.includeColor)
        assertTrue(mask.includeDetail)
        assertTrue(mask.includeEffects)
        assertTrue(mask.includePortraitSkin)
        assertTrue(mask.includeGeometryCropRotate)
        assertTrue(mask.includeLiquify)
        assertTrue(mask.includePortraitReshapeBody)
    }

    @Test
    fun shouldIncludeNothing_whenEveryBoxIsUnchecked() {
        val mask = CopyMask.fromCheckboxState(BooleanArray(9) { false })

        assertFalse(mask.includeBaseLook)
        assertFalse(mask.includeTone)
        assertFalse(mask.includeColor)
        assertFalse(mask.includeDetail)
        assertFalse(mask.includeEffects)
        assertFalse(mask.includePortraitSkin)
        assertFalse(mask.includeGeometryCropRotate)
        assertFalse(mask.includeLiquify)
        assertFalse(mask.includePortraitReshapeBody)
    }

    @Test
    fun shouldFallBackToTheDocumentedDefaults_whenTheChecklistIsShort() {
        val mask = CopyMask.fromCheckboxState(booleanArrayOf(true))

        assertEquals(CopyMask(), mask)
    }
}
