package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [OffscreenPreviewBreaker] — when the GPU preview must stop being attempted and
 * go straight to the CPU writer.
 */
class OffscreenPreviewBreakerTest {

    @Test
    fun shouldStayEnabled_whenFewerFailuresThanTheLimitAreRecorded() {
        val breaker = OffscreenPreviewBreaker(failureLimit = 3)

        breaker.onFailure()
        breaker.onFailure()

        assertTrue(breaker.isEnabled)
        assertEquals(2, breaker.failureCount)
    }

    @Test
    fun shouldDisableTheGpuPath_whenConsecutiveFailuresReachTheLimit() {
        val breaker = OffscreenPreviewBreaker(failureLimit = 3)

        repeat(3) { breaker.onFailure() }

        assertFalse(breaker.isEnabled)
        assertEquals(3, breaker.failureCount)
    }

    @Test
    fun shouldReArmTheGpuPath_whenARenderSucceeds() {
        val breaker = OffscreenPreviewBreaker(failureLimit = 2)

        breaker.onFailure()
        breaker.onSuccess()

        assertTrue(breaker.isEnabled)
        assertEquals(0, breaker.failureCount)
    }

    @Test
    fun shouldKeepTheFailureCountBounded_whenFailuresKeepArriving() {
        val breaker = OffscreenPreviewBreaker(failureLimit = 2)

        repeat(10) { breaker.onFailure() }

        assertFalse(breaker.isEnabled)
        assertEquals(2, breaker.failureCount)
    }

    @Test
    fun shouldEnableTheGpuPathAgain_whenResetIsRequested() {
        val breaker = OffscreenPreviewBreaker(failureLimit = 1)

        breaker.onFailure()
        assertFalse(breaker.isEnabled)
        breaker.reset()

        assertTrue(breaker.isEnabled)
    }
}
