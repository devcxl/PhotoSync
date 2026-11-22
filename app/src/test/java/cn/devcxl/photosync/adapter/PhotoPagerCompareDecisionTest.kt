package cn.devcxl.photosync.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [canComparePhoto] and [HoldGestureState] — the main-gallery Before/After decision
 * contract, expressed without `MotionEvent` so it runs on the JVM.
 */
class PhotoPagerCompareDecisionTest {

    private fun state(slop: Float = 8.0f) = HoldGestureState(slop)

    @Test
    fun shouldAllowCompareOnlyForPhotosWithAnEditedPreview_whenDeciding() {
        assertTrue(
            canComparePhoto(
                path = "/cache/DSC04821.ARW",
                hasEditPreview = true,
                previewOverridePath = "/cache/edits/7/preview_1.jpg"
            )
        )
    }

    @Test
    fun shouldRefuseCompare_whenPhotoWasNeverEdited() {
        assertFalse(
            canComparePhoto(
                path = "/cache/DSC04821.ARW",
                hasEditPreview = false,
                previewOverridePath = null
            )
        )
    }

    @Test
    fun shouldRefuseCompare_whenPreviewOverrideIsTheSourceItself() {
        assertFalse(
            canComparePhoto(
                path = "/cache/DSC04821.JPG",
                hasEditPreview = true,
                previewOverridePath = "/cache/DSC04821.JPG"
            )
        )
    }

    @Test
    fun shouldUseAHoldDelayAboveTheSpecMinimum_whenReadingTheConstant() {
        assertTrue(
            "a > 250ms hold is what the ticket asks for",
            PressAndHoldDetector.HOLD_DELAY_MS > 250L
        )
    }

    @Test
    fun shouldStartTheHold_whenTheTimerElapsesBeforeAnyMovement() {
        val gesture = state()

        assertTrue(gesture.onDown(100.0f, 100.0f))
        assertTrue(gesture.onHoldTimerElapsed())

        assertTrue(gesture.holding)
        assertFalse(gesture.armed)
    }

    @Test
    fun shouldCancelTheHold_whenTheFingerMovesBeyondTheSlop() {
        val gesture = state(slop = 8.0f)

        gesture.onDown(100.0f, 100.0f)
        val cancelled = gesture.onMove(130.0f, 100.0f)

        assertTrue(cancelled)
        assertFalse(gesture.onHoldTimerElapsed())
        assertFalse(gesture.holding)
    }

    @Test
    fun shouldKeepTheHoldArmed_whenMovementStaysInsideTheSlop() {
        val gesture = state(slop = 8.0f)

        gesture.onDown(100.0f, 100.0f)
        val cancelled = gesture.onMove(102.0f, 101.0f)

        assertFalse(cancelled)
        assertTrue(gesture.onHoldTimerElapsed())
    }

    @Test
    fun shouldCancelTheHold_whenASecondFingerLandsFirst() {
        val gesture = state()

        gesture.onDown(100.0f, 100.0f)
        val cancelled = gesture.onPointerDown()

        assertTrue(cancelled)
        assertFalse(gesture.onHoldTimerElapsed())
        assertFalse(gesture.holding)
    }

    @Test
    fun shouldReportHoldEnded_whenLiftingAfterTheHoldFired() {
        val gesture = state()

        gesture.onDown(100.0f, 100.0f)
        gesture.onHoldTimerElapsed()

        assertTrue(gesture.onUpOrCancel())
        assertFalse(gesture.holding)
    }

    @Test
    fun shouldReportNoEnd_whenLiftingBeforeTheHoldFired() {
        val gesture = state()

        gesture.onDown(100.0f, 100.0f)

        assertFalse(gesture.onUpOrCancel())
        assertFalse(gesture.onHoldTimerElapsed())
    }

    @Test
    fun shouldIgnoreLateTimerTicks_whenTheHoldWasAlreadyReleased() {
        val gesture = state()

        gesture.onDown(100.0f, 100.0f)
        gesture.onUpOrCancel()

        assertFalse(gesture.onHoldTimerElapsed())
        assertFalse(gesture.holding)
    }
}
