package cn.devcxl.photosync.activity

import cn.devcxl.photosync.R
import cn.devcxl.photosync.ptp.manager.CameraDeleteBlocker
import cn.devcxl.photosync.ptp.manager.CameraDeleteResult

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletePhotoDialogUiTest {

    @Test
    fun shouldExplainMissingSession_whenCameraIsNotConnected() {
        assertEquals(
            R.string.delete_on_camera_unavailable_not_connected,
            cameraDeleteBlockerMessage(CameraDeleteBlocker.NotConnected)
        )
    }

    @Test
    fun shouldExplainMissingCapability_whenCameraCannotDelete() {
        assertEquals(
            R.string.delete_on_camera_unavailable_not_supported,
            cameraDeleteBlockerMessage(CameraDeleteBlocker.NotSupported)
        )
    }

    @Test
    fun shouldExplainMissingHandle_whenPhotoWasImportedBeforeHandles() {
        assertEquals(
            R.string.delete_on_camera_unavailable_no_handle,
            cameraDeleteBlockerMessage(CameraDeleteBlocker.NoHandle)
        )
    }

    @Test
    fun shouldShowNoFailure_whenCameraDeletedTheObject() {
        assertNull(cameraDeleteFailureMessage(CameraDeleteResult.Deleted))
    }

    @Test
    fun shouldReportMissingObject_whenCameraNoLongerHasIt() {
        assertEquals(
            R.string.camera_delete_failed_not_found,
            cameraDeleteFailureMessage(CameraDeleteResult.NotFound)
        )
    }

    @Test
    fun shouldReportProtection_whenObjectIsWriteProtected() {
        assertEquals(
            R.string.camera_delete_failed_protected,
            cameraDeleteFailureMessage(CameraDeleteResult.Protected)
        )
    }

    @Test
    fun shouldReportReadOnlyCard_whenStorageRefusesDeletion() {
        assertEquals(
            R.string.camera_delete_failed_read_only,
            cameraDeleteFailureMessage(CameraDeleteResult.ReadOnly)
        )
    }

    @Test
    fun shouldFallBackToTheGenericMessage_whenTheCameraFailsForAnotherReason() {
        assertEquals(
            R.string.camera_delete_failed,
            cameraDeleteFailureMessage(CameraDeleteResult.Failed("GeneralError"))
        )
    }

    @Test
    fun shouldReportMissingSession_whenTheSessionIsGoneBeforeDeleting() {
        assertEquals(
            R.string.camera_delete_failed_not_connected,
            cameraDeleteFailureMessage(CameraDeleteResult.NotConnected)
        )
    }

    @Test
    fun shouldReportMissingCapability_whenTheCameraRejectsTheOperation() {
        assertEquals(
            R.string.camera_delete_failed_not_supported,
            cameraDeleteFailureMessage(CameraDeleteResult.NotSupported)
        )
    }

    @Test
    fun shouldPrecheckDeleteOnCamera_whenRememberedAndAvailable() {
        assertTrue(
            initialDeleteOnCameraChecked(
                canDeleteOnCamera = true,
                rememberedDeleteOnCamera = true
            )
        )
        assertFalse(
            initialDeleteOnCameraChecked(
                canDeleteOnCamera = true,
                rememberedDeleteOnCamera = false
            )
        )
        assertFalse(
            initialDeleteOnCameraChecked(
                canDeleteOnCamera = false,
                rememberedDeleteOnCamera = true
            )
        )
    }
}
