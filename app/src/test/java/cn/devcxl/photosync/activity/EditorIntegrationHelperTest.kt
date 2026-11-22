package cn.devcxl.photosync.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.PhotoEditRecord

class EditorIntegrationHelperTest {

    @Test
    fun shouldUsePreviewCachePath_whenEditRecordHasExistingPreviewFile() {
        val entity = PhotoEntity(id = 42L, path = "/cache/raw/DSC04821.ARW", name = "DSC04821.ARW")
        val record = PhotoEditRecord(
            photoId = 42L,
            schemaVersion = 1,
            graphJson = "{\"schema\":1}",
            previewCachePath = "/cache/edits/42/preview_100.jpg",
            updatedAt = 100L
        )
        val editedMap = mapOf(42L to record)

        val resolved = resolveEffectivePreviewPath(
            entity = entity,
            editedMap = editedMap,
            fileExists = { path -> path == "/cache/edits/42/preview_100.jpg" }
        )

        assertEquals("/cache/edits/42/preview_100.jpg", resolved)
        assertTrue(isPhotoMarkedEdited(entity, editedMap))
    }

    @Test
    fun shouldFallbackToOriginalPath_whenPhotoIsUneditedOrPreviewFileMissing() {
        val entity = PhotoEntity(id = 42L, path = "/cache/raw/DSC04821.ARW", name = "DSC04821.ARW")
        val record = PhotoEditRecord(
            photoId = 42L,
            schemaVersion = 1,
            graphJson = "{\"schema\":1}",
            previewCachePath = "/cache/edits/42/missing.jpg",
            updatedAt = 100L
        )

        assertEquals(
            "/cache/raw/DSC04821.ARW",
            resolveEffectivePreviewPath(
                entity = entity,
                editedMap = mapOf(42L to record),
                fileExists = { false }
            )
        )
        assertFalse(isPhotoMarkedEdited(entity, emptyMap()))
    }
}
