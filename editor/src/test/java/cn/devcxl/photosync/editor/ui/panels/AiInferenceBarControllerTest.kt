package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [AiInferenceBarController] — the non-blocking inference bar's progress and
 * cancel contract.
 */
class AiInferenceBarControllerTest {

    private class FakeHost : AiInferenceBarController.Host {
        var barVisible = false
        var labelText: CharSequence = ""
        var progressValue = -1
        var visibilityChanges = 0

        override fun setVisible(visible: Boolean) {
            barVisible = visible
            visibilityChanges++
        }

        override fun setLabel(label: CharSequence) {
            labelText = label
        }

        override fun setProgress(percent: Int) {
            progressValue = percent
        }
    }

    @Test
    fun shouldShowLabelAndClampedProgress_whenStartingAnInference() {
        val host = FakeHost()
        val controller = AiInferenceBarController(host) { }

        controller.show("MODNet", initialProgress = 8)

        assertTrue(controller.isVisible)
        assertTrue(host.barVisible)
        assertEquals("MODNet", host.labelText.toString())
        assertEquals(8, host.progressValue)
        assertFalse(controller.isCancelRequested)
    }

    @Test
    fun shouldClampProgressIntoTheBarRange_whenUpdatingIt() {
        val host = FakeHost()
        val controller = AiInferenceBarController(host) { }
        controller.show("MODNet")

        controller.updateProgress(150)
        assertEquals(100, controller.progress)
        assertEquals(100, host.progressValue)

        controller.updateProgress(-20)
        assertEquals(0, controller.progress)
        assertEquals(0, host.progressValue)
    }

    @Test
    fun shouldIgnoreProgressUpdates_whenTheBarIsHidden() {
        val host = FakeHost()
        val controller = AiInferenceBarController(host) { }

        controller.updateProgress(55)

        assertFalse(controller.isVisible)
        assertEquals(-1, host.progressValue)
    }

    @Test
    fun shouldCancelOnceAndHide_whenTheCancelButtonIsTappedRepeatedly() {
        val host = FakeHost()
        var cancelCount = 0
        val controller = AiInferenceBarController(host) { cancelCount++ }
        controller.show("MI-GAN", initialProgress = 30)

        controller.cancel()
        controller.cancel()

        assertEquals(1, cancelCount)
        assertTrue(controller.isCancelRequested)
        assertFalse(controller.isVisible)
        assertFalse(host.barVisible)
    }

    @Test
    fun shouldResetTheCancelFlag_whenShowingTheNextRun() {
        val host = FakeHost()
        val controller = AiInferenceBarController(host) { }
        controller.show("MI-GAN")
        controller.cancel()

        controller.show("MODNet", initialProgress = 5)

        assertTrue(controller.isVisible)
        assertFalse(controller.isCancelRequested)
        assertEquals(5, host.progressValue)
    }

    @Test
    fun shouldHideWithoutCancelling_whenTheRunFinishes() {
        val host = FakeHost()
        var cancelCount = 0
        val controller = AiInferenceBarController(host) { cancelCount++ }
        controller.show("MODNet")

        controller.dismiss()

        assertEquals(0, cancelCount)
        assertFalse(controller.isVisible)
        assertFalse(host.barVisible)
    }
}
