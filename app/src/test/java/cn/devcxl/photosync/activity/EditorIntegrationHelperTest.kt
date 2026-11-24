package cn.devcxl.photosync.activity

import java.io.File
import java.io.IOException
import java.nio.file.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

import cn.devcxl.photosync.adapter.PhotoViewerMode
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
            isReadableFile = { path -> path == "/cache/edits/42/preview_100.jpg" },
            resolvesToSameFile = { _, _ -> false }
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
                isReadableFile = { false }
            )
        )
        assertFalse(isPhotoMarkedEdited(entity, emptyMap()))
    }

    @Test
    fun shouldResolveEditedSource_whenRecordHasReadablePreview() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = mapOf(7L to record(previewCachePath = "/cache/edits/7/preview.jpg")),
            isReadableFile = { it == "/cache/edits/7/preview.jpg" },
            resolvesToSameFile = { _, _ -> false }
        )

        assertEquals(PreviewDisplaySource.EDITED, decision.source)
        assertEquals("/cache/edits/7/preview.jpg", decision.previewPath)
    }

    @Test
    fun shouldResolveOriginalSource_whenThereIsNoEditRecord() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = emptyMap(),
            isReadableFile = { true }
        )

        assertEquals(PreviewDisplaySource.ORIGINAL, decision.source)
        assertNull(decision.previewPath)
    }

    @Test
    fun shouldResolveUnavailable_whenRecordHasNoPreviewPath() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = mapOf(7L to record(previewCachePath = null)),
            isReadableFile = { true }
        )

        assertEquals(PreviewDisplaySource.UNAVAILABLE, decision.source)
        assertNull(decision.previewPath)
    }

    @Test
    fun shouldResolveUnavailable_whenPreviewPathIsBlank() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = mapOf(7L to record(previewCachePath = "   ")),
            isReadableFile = { true }
        )

        assertEquals(PreviewDisplaySource.UNAVAILABLE, decision.source)
        assertNull(decision.previewPath)
    }

    @Test
    fun shouldResolveUnavailable_whenPreviewPathPointsAtTheOriginal() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = mapOf(7L to record(previewCachePath = "/photos/photo.jpg")),
            isReadableFile = { true }
        )

        assertEquals(PreviewDisplaySource.UNAVAILABLE, decision.source)
        assertNull(decision.previewPath)
    }

    @Test
    fun shouldResolveUnavailable_whenRecordedPreviewFileIsGone() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = mapOf(7L to record(previewCachePath = "/cache/edits/7/preview.jpg")),
            isReadableFile = { false }
        )

        assertEquals(PreviewDisplaySource.UNAVAILABLE, decision.source)
        assertNull(decision.previewPath)
    }

    @Test
    fun shouldResolveEdited_whenPreviewIsADifferentReadableFile() {
        withTempDir { dir ->
            val original = File(dir, "photo.jpg").apply { writeText("original") }
            val preview = File(dir, "preview.jpg").apply { writeText("edited") }

            val decision = resolvePreviewDisplayDecision(
                entity = entityAt(original),
                editedMap = mapOf(7L to record(preview.absolutePath))
            )

            assertEquals(PreviewDisplaySource.EDITED, decision.source)
            assertEquals(preview.absolutePath, decision.previewPath)
        }
    }

    @Test
    fun shouldResolveUnavailable_whenPreviewPathIsADirectory() {
        withTempDir { dir ->
            val original = File(dir, "photo.jpg").apply { writeText("original") }
            val previewDir = File(dir, "preview.jpg").apply { mkdirs() }

            val decision = resolvePreviewDisplayDecision(
                entity = entityAt(original),
                editedMap = mapOf(7L to record(previewDir.absolutePath))
            )

            assertEquals(
                "a directory is not a readable preview file",
                PreviewDisplaySource.UNAVAILABLE,
                decision.source
            )
        }
    }

    @Test
    fun shouldResolveUnavailable_whenPreviewIsASymlinkToTheOriginal() {
        withTempDir { dir ->
            val original = File(dir, "photo.jpg").apply { writeText("original") }
            val alias = File(dir, "alias.jpg")
            val linked = try {
                Files.createSymbolicLink(alias.toPath(), original.toPath())
                true
            } catch (unsupported: UnsupportedOperationException) {
                Assume.assumeNoException("symlinks are not supported here", unsupported)
                false
            } catch (io: IOException) {
                Assume.assumeNoException("cannot create a symlink here", io)
                false
            } catch (denied: SecurityException) {
                Assume.assumeNoException("cannot create a symlink here", denied)
                false
            }
            if (!linked) return@withTempDir

            val decision = resolvePreviewDisplayDecision(
                entity = entityAt(original),
                editedMap = mapOf(7L to record(alias.absolutePath))
            )

            assertEquals(
                "a symlink to the original must not count as a preview",
                PreviewDisplaySource.UNAVAILABLE,
                decision.source
            )
        }
    }

    @Test
    fun shouldResolveUnavailable_whenPreviewIsALexicalAliasOfTheOriginal() {
        withTempDir { dir ->
            val original = File(dir, "photo.jpg").apply { writeText("original") }
            val alias = File(dir, "./photo.jpg").path
            assertNotEquals("the alias path must differ lexically", original.absolutePath, alias)

            val decision = resolvePreviewDisplayDecision(
                entity = entityAt(original),
                editedMap = mapOf(7L to record(alias))
            )

            assertEquals(
                "a relative alias to the original must not count as a preview",
                PreviewDisplaySource.UNAVAILABLE,
                decision.source
            )
        }
    }

    @Test
    fun shouldResolveUnavailable_whenReadabilityProbeThrows() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = mapOf(7L to record(previewCachePath = "/cache/edits/7/preview.jpg")),
            isReadableFile = { throw SecurityException("denied") },
            resolvesToSameFile = { _, _ -> false }
        )

        assertEquals(PreviewDisplaySource.UNAVAILABLE, decision.source)
        assertNull(decision.previewPath)
    }

    @Test
    fun shouldResolveUnavailable_whenCanonicalComparisonIsUnknown() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        val decision = resolvePreviewDisplayDecision(
            entity = entity,
            editedMap = mapOf(7L to record(previewCachePath = "/cache/edits/7/preview.jpg")),
            isReadableFile = { true },
            resolvesToSameFile = { _, _ -> null }
        )

        assertEquals(PreviewDisplaySource.UNAVAILABLE, decision.source)
        assertNull(decision.previewPath)
    }

    @Test
    fun shouldRecoverToEdited_whenPreviewFileBecomesReadable() {
        withTempDir { dir ->
            val original = File(dir, "photo.jpg").apply { writeText("original") }
            val preview = File(dir, "preview.jpg")
            val entity = entityAt(original)
            val editedMap = mapOf(7L to record(preview.absolutePath))

            assertEquals(
                PreviewDisplaySource.UNAVAILABLE,
                resolvePreviewDisplayDecision(entity, editedMap).source
            )

            preview.writeText("edited")

            val recovered = resolvePreviewDisplayDecision(entity, editedMap)
            assertEquals(PreviewDisplaySource.EDITED, recovered.source)
            assertEquals(preview.absolutePath, recovered.previewPath)
        }
    }

    @Test
    fun shouldNotTreatUnreadablePreviewAsEdited_whenFilePermissionsDenyReading() {
        withTempDir { dir ->
            val original = File(dir, "photo.jpg").apply { writeText("original") }
            val preview = File(dir, "preview.jpg").apply { writeText("edited") }
            preview.setReadable(false)
            Assume.assumeFalse(
                "this user can read regardless of file permissions",
                preview.canRead()
            )

            val decision = resolvePreviewDisplayDecision(
                entity = entityAt(original),
                editedMap = mapOf(7L to record(preview.absolutePath))
            )

            assertEquals(PreviewDisplaySource.UNAVAILABLE, decision.source)
        }
    }

    @Test
    fun shouldUseBitmapViewer_whenJpegHasEditRecordEvenWithoutReadablePreview() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")
        val editedMap = mapOf(7L to record(previewCachePath = null))

        assertEquals(
            "the record, not the preview file, decides the viewer",
            PhotoViewerMode.PHOTO_VIEW,
            resolveGalleryViewerMode(entity, editedMap, isJpeg = true)
        )
    }

    @Test
    fun shouldUseTiledViewer_whenJpegHasNoEditRecord() {
        val entity = PhotoEntity(id = 7L, path = "/photos/photo.jpg", name = "photo.jpg")

        assertEquals(
            PhotoViewerMode.JPEG_TILED,
            resolveGalleryViewerMode(entity, emptyMap(), isJpeg = true)
        )
        assertEquals(
            PhotoViewerMode.PHOTO_VIEW,
            resolveGalleryViewerMode(entity, emptyMap(), isJpeg = false)
        )
    }

    private fun record(previewCachePath: String?): PhotoEditRecord = PhotoEditRecord(
        photoId = 7L,
        schemaVersion = 1,
        graphJson = "{\"schema\":1}",
        previewCachePath = previewCachePath,
        updatedAt = 100L
    )

    private fun entityAt(path: File): PhotoEntity =
        PhotoEntity(id = 7L, path = path.absolutePath, name = path.name)

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("photosync-preview").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
