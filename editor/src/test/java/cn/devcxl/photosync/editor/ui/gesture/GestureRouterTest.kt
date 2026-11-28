package cn.devcxl.photosync.editor.ui.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the two-finger preemption rule of the editor canvas, which must guarantee that
 * pinching to zoom never smears a crop handle or any other single-finger tool.
 */
class GestureRouterTest {

    @Test
    fun shouldAlwaysRouteToCanvasPanZoom_whenTwoOrMoreFingersAreDown() {
        for (group in EditorGroup.entries) {
            for (tool in EditorTool.entries) {
                for (pointerCount in listOf(2, 3, 5)) {
                    val route = GestureRouter.resolveGestureRoute(group, tool, pointerCount)
                    assertEquals(
                        "group=$group tool=$tool pointers=$pointerCount",
                        GestureRoute.CANVAS_TWO_FINGER_PAN_ZOOM,
                        route
                    )
                }
            }
        }
    }

    @Test
    fun shouldRouteToCropPerspectiveHandle_whenGeometryCropStraightenOrPerspectiveIsActive() {
        listOf(EditorTool.CROP, EditorTool.STRAIGHTEN, EditorTool.PERSPECTIVE).forEach { tool ->
            assertEquals(
                "tool=$tool",
                GestureRoute.CROP_PERSPECTIVE_HANDLE,
                GestureRouter.resolveGestureRoute(EditorGroup.GEOMETRY, tool, pointerCount = 1)
            )
        }
    }

    @Test
    fun shouldRouteToHoldCompare_whenAGeometryInstantActionIsActive() {
        listOf(EditorTool.ROTATE90, EditorTool.FLIP_HORIZONTAL, EditorTool.FLIP_VERTICAL).forEach { tool ->
            assertEquals(
                "tool=$tool",
                GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE,
                GestureRouter.resolveGestureRoute(EditorGroup.GEOMETRY, tool, pointerCount = 1)
            )
        }
    }

    @Test
    fun shouldNeverRouteToCropHandle_whenAToolOutsideGeometryIsActive() {
        for (group in EditorGroup.entries) {
            if (group == EditorGroup.GEOMETRY) continue
            for (tool in EditorTool.entries) {
                if (group == EditorGroup.AI && tool == EditorTool.AI_ERASE) continue
                assertEquals(
                    "group=$group tool=$tool",
                    GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE,
                    GestureRouter.resolveGestureRoute(group, tool, pointerCount = 1)
                )
            }
        }
    }

    @Test
    fun shouldRouteToInpaintBrushStroke_whenAiEraseIsActive() {
        assertEquals(
            GestureRoute.INPAINT_BRUSH_STROKE,
            GestureRouter.resolveGestureRoute(EditorGroup.AI, EditorTool.AI_ERASE, pointerCount = 1)
        )
        assertEquals(
            "the erase brush must never claim a two-finger gesture",
            GestureRoute.CANVAS_TWO_FINGER_PAN_ZOOM,
            GestureRouter.resolveGestureRoute(EditorGroup.AI, EditorTool.AI_ERASE, pointerCount = 2)
        )
        assertEquals(
            "the subject cut-out keeps the plain canvas",
            GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE,
            GestureRouter.resolveGestureRoute(EditorGroup.AI, EditorTool.AI_SUBJECT, pointerCount = 1)
        )
    }

    @Test
    fun shouldTreatZeroOrOnePointerAsSingleFinger_whenResolvingTheRoute() {
        assertEquals(
            GestureRoute.CROP_PERSPECTIVE_HANDLE,
            GestureRouter.resolveGestureRoute(EditorGroup.GEOMETRY, EditorTool.CROP, pointerCount = 0)
        )
        assertEquals(
            GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE,
            GestureRouter.resolveGestureRoute(EditorGroup.TONE, EditorTool.EXPOSURE, pointerCount = 0)
        )
    }

    @Test
    fun shouldRouteToLiquifyBrushStroke_whenGeometryLiquifyIsActive() {
        assertEquals(
            GestureRoute.LIQUIFY_BRUSH_STROKE,
            GestureRouter.resolveGestureRoute(EditorGroup.GEOMETRY, EditorTool.LIQUIFY, pointerCount = 1)
        )
    }

    @Test
    fun shouldRollBackTheUncommittedStroke_whenASecondPointerArrivesBelowEightDp() {
        val density = 2.75f
        val arbiter = LiquifyStrokeArbiter(GestureRouter.preemptionSlopPx(density))

        arbiter.beginStroke()
        arbiter.onMove(6.0f * density)
        arbiter.onMove(1.5f * density)

        assertEquals(LiquifyPreemption.ROLL_BACK_UNCOMMITTED_STROKE, arbiter.onSecondPointerDown())
        assertFalse("the rolled-back stroke must not be committed", arbiter.isStrokeActive)
    }

    @Test
    fun shouldCommitTheStroke_whenASecondPointerArrivesAtOrAboveEightDp() {
        val density = 2.75f
        val arbiter = LiquifyStrokeArbiter(GestureRouter.preemptionSlopPx(density))

        arbiter.beginStroke()
        arbiter.onMove(8.0f * density)

        assertEquals(LiquifyPreemption.KEEP_STROKE, arbiter.onSecondPointerDown())
        assertFalse("the committed stroke is sealed once the pinch takes over", arbiter.isStrokeActive)
    }

    @Test
    fun shouldCommitSingleFingerDabBelowEightDpAndSkipStationaryTap_whenFingerLiftsWithoutSecondPointer() {
        val density = 2.0f
        val arbiter = LiquifyStrokeArbiter(GestureRouter.preemptionSlopPx(density))

        // Deliberate 3dp micro-retouch without a second finger must commit
        arbiter.beginStroke()
        arbiter.onMove(3.0f * density)
        assertTrue(arbiter.endStroke())

        // Stationary 0px tap produces no undo step (allows double-tap reset)
        val tap = LiquifyStrokeArbiter(GestureRouter.preemptionSlopPx(density))
        tap.beginStroke()
        assertFalse("a tap without movement produces no undo step", tap.endStroke())
    }
}
