package cn.devcxl.photosync.ptp.manager

import cn.devcxl.photosync.ptp.usbcamera.Response

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraDeletionPolicyTest {

    @Test
    fun shouldBlockDeletion_whenNoSessionIsOpen() {
        val blocker = cameraDeleteBlockedBy(
            connected = false,
            cameraSupportsDelete = true,
            hasObjectHandle = true
        )

        assertEquals(CameraDeleteBlocker.NotConnected, blocker)
    }

    @Test
    fun shouldBlockDeletion_whenCameraDoesNotImplementDeleteObject() {
        val blocker = cameraDeleteBlockedBy(
            connected = true,
            cameraSupportsDelete = false,
            hasObjectHandle = true
        )

        assertEquals(CameraDeleteBlocker.NotSupported, blocker)
    }

    @Test
    fun shouldBlockDeletion_whenPhotoHasNoObjectHandle() {
        val blocker = cameraDeleteBlockedBy(
            connected = true,
            cameraSupportsDelete = true,
            hasObjectHandle = false
        )

        assertEquals(CameraDeleteBlocker.NoHandle, blocker)
    }

    @Test
    fun shouldOfferDeletion_whenSessionCameraAndHandleAreAllPresent() {
        val blocker = cameraDeleteBlockedBy(
            connected = true,
            cameraSupportsDelete = true,
            hasObjectHandle = true
        )

        assertNull(blocker)
    }

    @Test
    fun shouldReportDeleted_whenResponseIsOk() {
        assertEquals(CameraDeleteResult.Deleted, cameraDeleteResultOf(Response.OK))
    }

    @Test
    fun shouldReportNotFound_whenHandleIsInvalid() {
        assertEquals(
            CameraDeleteResult.NotFound,
            cameraDeleteResultOf(Response.InvalidObjectHandle)
        )
    }

    @Test
    fun shouldReportProtected_whenObjectIsWriteProtected() {
        assertEquals(
            CameraDeleteResult.Protected,
            cameraDeleteResultOf(Response.ObjectWriteProtected)
        )
    }

    @Test
    fun shouldReportReadOnly_whenStoreIsReadOnly() {
        assertEquals(CameraDeleteResult.ReadOnly, cameraDeleteResultOf(Response.StoreReadOnly))
    }

    @Test
    fun shouldReportReadOnly_whenAccessIsDenied() {
        assertEquals(CameraDeleteResult.ReadOnly, cameraDeleteResultOf(Response.AccessDenied))
    }

    @Test
    fun shouldReportNotSupported_whenCameraRejectsTheOperation() {
        assertEquals(
            CameraDeleteResult.NotSupported,
            cameraDeleteResultOf(Response.OperationNotSupported)
        )
    }

    @Test
    fun shouldReportFailureWithTheCodeName_whenResponseIsUnexpected() {
        val result = cameraDeleteResultOf(Response.GeneralError)

        assertEquals(CameraDeleteResult.Failed("GeneralError"), result)
    }
}
