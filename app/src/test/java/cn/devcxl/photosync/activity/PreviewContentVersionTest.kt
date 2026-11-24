package cn.devcxl.photosync.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

import cn.devcxl.photosync.adapter.PhotoContentVersion
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.PhotoEditRecord

/**
 * Content versions the pager receives from [MainActivity].
 *
 * The version must follow the record and the resolved source, not the clock: saving a new edit,
 * resetting an edit, and losing the preview file each produce a distinct version, while an
 * unchanged record keeps producing the same one.
 */
class PreviewContentVersionTest {

    @Test
    fun shouldVersionTheOriginal_whenThereIsNoRecord() {
        assertEquals(
            PhotoContentVersion.Original,
            resolvePhotoContentVersion(decision(entity = ENTITY, editedMap = emptyMap()))
        )
    }

    @Test
    fun shouldVersionTheEditedPreviewByItsExactPath() {
        val version = resolvePhotoContentVersion(
            decision(entity = ENTITY, editedMap = mapOf(ENTITY.id to record(PREVIEW_A)))
        )

        assertEquals(PhotoContentVersion.Edited(PREVIEW_A), version)
        assertEquals(
            "the same record must keep comparing equal across decodes",
            version,
            resolvePhotoContentVersion(
                decision(entity = ENTITY, editedMap = mapOf(ENTITY.id to record(PREVIEW_A)))
            )
        )
    }

    @Test
    fun shouldGiveEachRenderedPreviewItsOwnVersion() {
        val first = resolvePhotoContentVersion(
            decision(entity = ENTITY, editedMap = mapOf(ENTITY.id to record(PREVIEW_A)))
        )
        val second = resolvePhotoContentVersion(
            decision(entity = ENTITY, editedMap = mapOf(ENTITY.id to record(PREVIEW_B)))
        )

        assertNotEquals("a newly rendered preview is new content", first, second)
    }

    @Test
    fun shouldVersionTheUnavailableState_whenThePreviewFileIsMissing() {
        val version = resolvePhotoContentVersion(
            decision(
                entity = ENTITY,
                editedMap = mapOf(ENTITY.id to record(MISSING_PREVIEW)),
                previewExists = false
            )
        )

        assertEquals(PhotoContentVersion.PreviewUnavailable, version)
    }

    @Test
    fun shouldChangeTheVersion_whenTheEditIsResetToTheOriginal() {
        val edited = resolvePhotoContentVersion(
            decision(entity = ENTITY, editedMap = mapOf(ENTITY.id to record(PREVIEW_A)))
        )
        val reset = resolvePhotoContentVersion(decision(entity = ENTITY, editedMap = emptyMap()))

        assertNotEquals("releasing a held stale frame must not be possible after a reset", edited, reset)
        assertEquals(PhotoContentVersion.Original, reset)
    }

    @Test
    fun shouldChangeTheVersion_whenARecoveredPreviewRepairsAnUnavailableRecord() {
        val unavailable = resolvePhotoContentVersion(
            decision(
                entity = ENTITY,
                editedMap = mapOf(ENTITY.id to record(MISSING_PREVIEW)),
                previewExists = false
            )
        )
        val recovered = resolvePhotoContentVersion(
            decision(entity = ENTITY, editedMap = mapOf(ENTITY.id to record(PREVIEW_A)))
        )

        assertNotEquals(unavailable, recovered)
    }

    private fun decision(
        entity: PhotoEntity,
        editedMap: Map<Long, PhotoEditRecord>,
        previewExists: Boolean = true
    ): PreviewDisplayDecision {
        return resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = editedMap,
            isReadableFile = { path -> previewExists && path in setOf(PREVIEW_A, PREVIEW_B) },
            resolvesToSameFile = { _, _ -> false }
        )
    }

    private fun record(previewPath: String?): PhotoEditRecord = PhotoEditRecord(
        photoId = ENTITY.id,
        schemaVersion = 1,
        graphJson = "{\"schema\":1}",
        previewCachePath = previewPath,
        updatedAt = 100L
    )

    private companion object {
        val ENTITY = PhotoEntity(id = 42L, path = "/cache/raw/DSC04821.ARW", name = "DSC04821.ARW")
        const val PREVIEW_A = "/cache/edits/42/preview_a.jpg"
        const val PREVIEW_B = "/cache/edits/42/preview_b.jpg"
        const val MISSING_PREVIEW = "/cache/edits/42/missing.jpg"
    }
}
