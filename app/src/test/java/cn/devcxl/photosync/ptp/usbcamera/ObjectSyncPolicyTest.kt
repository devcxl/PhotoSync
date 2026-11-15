package cn.devcxl.photosync.ptp.usbcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectSyncPolicyTest {

    @Test
    fun shouldReportOnlyUnhandledObjects_whenSomeAreAlreadyImported() {
        val added = newObjectHandles(handled = listOf(1, 2), onCamera = listOf(1, 2, 3))

        assertEquals(listOf(3), added)
    }

    @Test
    fun shouldNotReportAnything_whenTheCameraIsUnchanged() {
        val added = newObjectHandles(handled = listOf(1, 2), onCamera = listOf(1, 2))

        assertEquals(emptyList<Int>(), added)
    }

    @Test
    fun shouldDropHandle_whenTheObjectIsGoneFromTheCamera() {
        val next = nextHandledHandles(
            handled = listOf(1, 2, 3),
            onCamera = listOf(1, 3),
            imported = emptyList()
        )

        assertEquals(listOf(1, 3), next)
    }

    @Test
    fun shouldReportDeletedHandleAsNew_whenTheCameraReusesIt() {
        // The deletion has been reconciled away, so the reused handle must look new again.
        val afterDelete = nextHandledHandles(
            handled = listOf(1, 5),
            onCamera = listOf(1),
            imported = emptyList()
        )

        val added = newObjectHandles(handled = afterDelete, onCamera = listOf(1, 5))

        assertEquals(listOf(5), added)
    }

    @Test
    fun shouldKeepRetryingHandle_whenItsDownloadFailed() {
        val next = nextHandledHandles(
            handled = listOf(1),
            onCamera = listOf(1, 2),
            imported = emptyList()
        )

        assertEquals(listOf(1), next)
        assertEquals(listOf(2), newObjectHandles(handled = next, onCamera = listOf(1, 2)))
    }

    @Test
    fun shouldMarkHandleHandled_whenItsDownloadSucceeded() {
        val next = nextHandledHandles(
            handled = listOf(1),
            onCamera = listOf(1, 2),
            imported = listOf(2)
        )

        assertEquals(listOf(1, 2), next)
    }

    @Test
    fun shouldKeepCameraOrder_whenReconciling() {
        val next = nextHandledHandles(
            handled = listOf(3, 1),
            onCamera = listOf(1, 2, 3),
            imported = listOf(2)
        )

        assertEquals(listOf(1, 2, 3), next)
    }

    @Test
    fun shouldAddHandleToTheBaseline_whenAnEventModeImportSucceeded() {
        val next = withImportedHandle(handled = listOf(1, 2), imported = 3)

        assertEquals(listOf(1, 2, 3), next)
    }

    @Test
    fun shouldKeepOneEntry_whenTheSameHandleIsImportedTwice() {
        val next = withImportedHandle(handled = listOf(1, 2), imported = 2)

        assertEquals(listOf(1, 2), next)
    }
}
