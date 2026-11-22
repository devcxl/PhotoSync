package cn.devcxl.photosync.editor.ui.gesture

import org.junit.Assert.assertEquals
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
                assertEquals(
                    "group=$group tool=$tool",
                    GestureRoute.CANVAS_SINGLE_PAN_HOLD_COMPARE,
                    GestureRouter.resolveGestureRoute(group, tool, pointerCount = 1)
                )
            }
        }
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
}
