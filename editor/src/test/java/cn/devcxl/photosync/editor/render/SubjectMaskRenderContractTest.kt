package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.export.BakeOutputFormat
import cn.devcxl.photosync.editor.export.BakeOutputNaming

/**
 * Seam under test: the subject render contract shared by the canvas, the cached preview and the
 * full-resolution bake.
 *
 * The test locks the three facts that must never drift:
 * 1. `ORIGINAL` and a missing mask are a zero-cost short circuit;
 * 2. the subject composite sits **before** LUT / grain / vignette in Pass D;
 * 3. transparent exports keep their alpha and route to the PNG container.
 */
class SubjectMaskRenderContractTest {

    @Test
    fun shouldReturnNull_whenTheBackgroundIsOriginal() {
        val graph = EditGraph(subject = SubjectOps(maskRef = "edits/1/subject_mask_x.png"))

        assertNull(SubjectRenderContract.plan(graph, hasMask = true))
    }

    @Test
    fun shouldReturnNull_whenTheMaskIsMissing() {
        val graph = EditGraph(subject = SubjectOps(bgMode = SubjectBackgroundMode.TRANSPARENT))

        assertNull(SubjectRenderContract.plan(graph, hasMask = true))
        assertNull(SubjectRenderContract.plan(graph.copy(subject = SubjectOps()), hasMask = false))
    }

    @Test
    fun shouldOrderTheSubjectBeforeLutGrainAndVignette_whenSolidBackgroundIsActive() {
        val graph = EditGraph(
            subject = SubjectOps(
                maskRef = "edits/1/subject_mask_abc.png",
                bgMode = SubjectBackgroundMode.SOLID,
                bgColorArgb = 0xFF203040.toInt()
            )
        )

        val plan = SubjectRenderContract.plan(graph, hasMask = true)!!

        assertEquals(SubjectBackgroundMode.SOLID, plan.mode)
        assertEquals(0x203040, plan.solidColorRgb)
        assertEquals(1, plan.shaderMode)
        assertFalse(plan.transparentAlpha)
        assertEquals(
            listOf(
                SubjectPassStage.SUBJECT_COMPOSITE,
                SubjectPassStage.LUT,
                SubjectPassStage.GRAIN,
                SubjectPassStage.VIGNETTE
            ),
            plan.passOrder
        )
    }

    @Test
    fun shouldKeepAlphaAndRouteToPng_whenTransparentIsActive() {
        val graph = EditGraph(
            subject = SubjectOps(
                maskRef = "edits/1/subject_mask_abc.png",
                bgMode = SubjectBackgroundMode.TRANSPARENT
            )
        )

        val plan = SubjectRenderContract.plan(graph, hasMask = true)!!

        assertEquals(2, plan.shaderMode)
        assertTrue(plan.transparentAlpha)
        assertTrue(SubjectRenderContract.exportsAlpha(graph))
        assertEquals(BakeOutputFormat.PNG_RGBA, BakeOutputNaming.outputFormatFor(graph))
    }

    @Test
    fun shouldRouteOpaqueBackgroundsToJpeg() {
        val solid = EditGraph(
            subject = SubjectOps(
                maskRef = "edits/1/subject_mask_abc.png",
                bgMode = SubjectBackgroundMode.SOLID
            )
        )
        val original = EditGraph()

        assertEquals(BakeOutputFormat.JPEG, BakeOutputNaming.outputFormatFor(solid))
        assertEquals(BakeOutputFormat.JPEG, BakeOutputNaming.outputFormatFor(original))
        assertFalse(SubjectRenderContract.exportsAlpha(solid))
    }

    @Test
    fun shouldPackForegroundAsRgbAndRefinedAlphaAsA_whenUploadingTheSubjectTexture() {
        val composite = SubjectComposite(
            width = 1,
            height = 1,
            alpha = floatArrayOf(0.5f),
            foreground = floatArrayOf(1.0f, 0.0f, 0.0f),
            background = floatArrayOf(0.0f, 0.0f, 1.0f)
        )

        assertEquals(0xFF000080.toInt(), composite.toRgba8()[0])
    }

    @Test
    fun shouldDrawTheCheckerboard_whenTransparentPixelsAreCompositedOnTheCpu() {
        val composite = SubjectComposite(
            width = 4,
            height = 4,
            alpha = FloatArray(16) { 0.0f },
            foreground = FloatArray(16 * 3) { 1.0f },
            background = FloatArray(16 * 3) { 0.0f }
        )
        val graph = EditGraph(
            subject = SubjectOps(maskRef = "mask.png", bgMode = SubjectBackgroundMode.TRANSPARENT)
        )
        val pixels = IntArray(4 * 4) { 0xFF000000.toInt() }

        val composited = SubjectCpuComposer.applyForeground(
            pixels = pixels,
            width = 4,
            height = 4,
            graph = graph,
            composite = composite,
            inverseMatrix = Mat3.identity()
        )
        SubjectCpuComposer.drawCheckerboard(composited, 4, 4)

        assertEquals(0xFF2E2E2E.toInt(), composited[0])
    }

    @Test
    fun shouldCompositeTheSolidColour_whenAlphaIsFullyTransparent() {
        val composite = SubjectComposite(
            width = 2,
            height = 2,
            alpha = FloatArray(4) { 0.0f },
            foreground = FloatArray(12) { 1.0f },
            background = FloatArray(12) { 0.0f }
        )
        val graph = EditGraph(
            subject = SubjectOps(
                maskRef = "mask.png",
                bgMode = SubjectBackgroundMode.SOLID,
                bgColorArgb = 0xFF102030.toInt()
            )
        )

        val composited = SubjectCpuComposer.applyForeground(
            pixels = IntArray(4) { 0xFF000000.toInt() },
            width = 2,
            height = 2,
            graph = graph,
            composite = composite,
            inverseMatrix = Mat3.identity()
        )

        assertEquals(0xFF102030.toInt(), composited[0])
    }
}
