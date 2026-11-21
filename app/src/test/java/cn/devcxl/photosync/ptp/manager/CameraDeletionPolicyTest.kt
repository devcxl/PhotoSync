package cn.devcxl.photosync.ptp.manager

import cn.devcxl.photosync.ptp.protocol.PtpStatus

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
        assertEquals(CameraDeleteResult.Deleted, cameraDeleteResultOf(PtpStatus.OK))
    }

    @Test
    fun shouldReportNotFound_whenHandleIsInvalid() {
        assertEquals(
            CameraDeleteResult.NotFound,
            cameraDeleteResultOf(PtpStatus.INVALID_OBJECT_HANDLE)
        )
    }

    @Test
    fun shouldReportProtected_whenObjectIsWriteProtected() {
        assertEquals(
            CameraDeleteResult.Protected,
            cameraDeleteResultOf(PtpStatus.OBJECT_WRITE_PROTECTED)
        )
    }

    @Test
    fun shouldReportReadOnly_whenStoreIsReadOnly() {
        assertEquals(CameraDeleteResult.ReadOnly, cameraDeleteResultOf(PtpStatus.STORE_READ_ONLY))
    }

    @Test
    fun shouldReportReadOnly_whenAccessIsDenied() {
        assertEquals(CameraDeleteResult.ReadOnly, cameraDeleteResultOf(PtpStatus.ACCESS_DENIED))
    }

    @Test
    fun shouldReportNotSupported_whenCameraRejectsTheOperation() {
        assertEquals(
            CameraDeleteResult.NotSupported,
            cameraDeleteResultOf(PtpStatus.OPERATION_NOT_SUPPORTED)
        )
    }

    @Test
    fun shouldReportFailureWithTheCodeName_whenResponseIsUnexpected() {
        val result = cameraDeleteResultOf(PtpStatus.GENERAL_ERROR)

        assertEquals(CameraDeleteResult.Failed("GeneralError"), result)
    }
}
