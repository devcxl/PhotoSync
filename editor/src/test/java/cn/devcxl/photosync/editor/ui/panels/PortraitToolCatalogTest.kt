package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.BodyOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.PortraitOps
import cn.devcxl.photosync.editor.domain.ReshapeOps
import cn.devcxl.photosync.editor.domain.SkinOps
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.gesture.EditorTool

/**
 * Seam under test: [EditorToolCatalog] for the Portrait group — three sub-tools, one undo bucket
 * each, dedicated multi-row panels and independent resets.
 */
class PortraitToolCatalogTest {

    @Test
    fun shouldExposeThreePortraitToolsWithDistinctUndoBuckets_whenInspectingTheCatalog() {
        val keys = portraitSpecs().map { it.parameterKey }

        assertEquals(
            listOf(
                EditorTool.SKIN_SMOOTH,
                EditorTool.FACE_RESHAPE,
                EditorTool.BODY_RESHAPE
            ),
            portraitSpecs().map { it.tool }
        )
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(
            setOf(
                EditParameterKey.PORTRAIT_SKIN,
                EditParameterKey.PORTRAIT_RESHAPE,
                EditParameterKey.PORTRAIT_BODY
            ),
            keys.toSet()
        )
        assertTrue(portraitSpecs().all { it.group == EditorGroup.PORTRAIT })
    }

    @Test
    fun shouldDriveEveryPortraitToolThroughItsOwnPanel_whenInspectingTheCatalog() {
        assertEquals(EditorControlStyle.SKIN, specFor(EditorTool.SKIN_SMOOTH).controlStyle)
        assertEquals(EditorControlStyle.FACE_RESHAPE, specFor(EditorTool.FACE_RESHAPE).controlStyle)
        assertEquals(EditorControlStyle.BODY_RESHAPE, specFor(EditorTool.BODY_RESHAPE).controlStyle)
        portraitSpecs().forEach { assertNull(it.tool.name, it.dial) }
    }

    @Test
    fun shouldResetOnlyItsOwnPortraitBranch_whenTheChipIsDoubleTapped() {
        val busy = EditGraph(
            portrait = PortraitOps(
                skin = SkinOps(smooth = 40, texture = 80, evenTone = 30),
                reshape = ReshapeOps(face = 20, eyes = -30),
                body = BodyOps(waist = 25, legs = 60, waistY = 0.44f, hipY = 0.58f)
            )
        )

        val withoutSkin = reset(EditorTool.SKIN_SMOOTH, busy)
        assertEquals(SkinOps(), withoutSkin.portrait.skin)
        assertEquals(20, withoutSkin.portrait.reshape.face)
        assertEquals(25, withoutSkin.portrait.body.waist)

        val withoutReshape = reset(EditorTool.FACE_RESHAPE, busy)
        assertEquals(ReshapeOps(), withoutReshape.portrait.reshape)
        assertEquals(40, withoutReshape.portrait.skin.smooth)

        val withoutBody = reset(EditorTool.BODY_RESHAPE, busy)
        assertEquals(BodyOps(), withoutBody.portrait.body)
        assertEquals(60, busy.portrait.body.legs)
        assertEquals(-30, withoutBody.portrait.reshape.eyes)
    }

    @Test
    fun shouldReportModifiedOnlyForItsOwnPortraitBranch_whenReadingChipState() {
        val graph = EditGraph(
            portrait = PortraitOps(
                skin = SkinOps(texture = 70),
                reshape = ReshapeOps(nose = 1),
                body = BodyOps(waistY = 0.5f)
            )
        )

        assertTrue(specFor(EditorTool.SKIN_SMOOTH).isModified(graph))
        assertTrue(specFor(EditorTool.FACE_RESHAPE).isModified(graph))
        assertTrue(specFor(EditorTool.BODY_RESHAPE).isModified(graph))
        assertFalse(specFor(EditorTool.SKIN_SMOOTH).isModified(EditGraph()))
        assertFalse(specFor(EditorTool.FACE_RESHAPE).isModified(EditGraph()))
    }

    @Test
    fun shouldCreateOneUndoStepPerPortraitBucket_whenThreePanelsAreDraggedInsideOneWindow() {
        val controller = EditorUndoController()
        val skin = specFor(EditorTool.SKIN_SMOOTH)
        val reshape = specFor(EditorTool.FACE_RESHAPE)

        controller.onParameterDrag(
            controller.currentGraph.copy(portrait = PortraitOps(skin = SkinOps(smooth = 30))),
            skin.parameterKey,
            timestampMs = 1000L
        )
        val afterSkin = controller.currentGraph
        controller.onParameterDrag(
            afterSkin.copy(portrait = afterSkin.portrait.copy(reshape = ReshapeOps(face = 40))),
            reshape.parameterKey,
            timestampMs = 1040L
        )

        assertEquals(2, controller.undoDepth)
        assertEquals(0, controller.undo().portrait.reshape.face)
        assertEquals(30, controller.currentGraph.portrait.skin.smooth)
    }

    private fun specFor(tool: EditorTool): EditorToolSpec =
        EditorToolCatalog.specFor(tool) ?: error("Missing spec for $tool")

    private fun reset(tool: EditorTool, graph: EditGraph): EditGraph =
        specFor(tool).reset(graph)

    private fun portraitSpecs(): List<EditorToolSpec> =
        EditorToolCatalog.toolsFor(EditorGroup.PORTRAIT)
}
