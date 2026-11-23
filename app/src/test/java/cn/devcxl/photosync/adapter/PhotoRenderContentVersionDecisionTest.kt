package cn.devcxl.photosync.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decisions for a same-path content version change.
 *
 * A new preview for a file the page already shows must win immediately, whatever the zoom state:
 * the bitmap on screen and any deferred full frame belong to the previous version. Within one
 * version the old lazy thumbnail-to-full upgrade stays.
 */
class PhotoRenderContentVersionDecisionTest {

    @Test
    fun shouldApplyTheNewThumbnailImmediately_whenTheVersionChangesWhileZoomed() {
        val decision = resolvePhotoRenderDecision(
            currentPath = PATH,
            currentStage = PhotoRenderStage.FULL,
            currentContentVersion = VERSION_A,
            nextPath = PATH,
            nextContentVersion = VERSION_B,
            hasThumbnail = true,
            hasFull = false,
            isZoomed = true
        )

        assertEquals(PhotoRenderStage.THUMBNAIL, decision.stage)
        assertTrue("the old full frame must not survive the new version", decision.shouldApplyImmediately)
    }

    @Test
    fun shouldApplyTheNewFullImmediately_whenTheVersionChangesWhileZoomed() {
        val decision = resolvePhotoRenderDecision(
            currentPath = PATH,
            currentStage = PhotoRenderStage.THUMBNAIL,
            currentContentVersion = VERSION_A,
            nextPath = PATH,
            nextContentVersion = VERSION_B,
            hasThumbnail = true,
            hasFull = true,
            isZoomed = true
        )

        assertEquals(PhotoRenderStage.FULL, decision.stage)
        assertTrue("a full of the new version must not wait for the gesture to end", decision.shouldApplyImmediately)
    }

    @Test
    fun shouldClearImmediately_whenTheVersionChangesToAnEmptyState() {
        val decision = resolvePhotoRenderDecision(
            currentPath = PATH,
            currentStage = PhotoRenderStage.FULL,
            currentContentVersion = VERSION_A,
            nextPath = PATH,
            nextContentVersion = VERSION_B,
            hasThumbnail = false,
            hasFull = false,
            isZoomed = true
        )

        assertEquals(PhotoRenderStage.EMPTY, decision.stage)
        assertTrue(decision.shouldApplyImmediately)
    }

    @Test
    fun shouldStillDeferTheFullUpgrade_whenTheVersionDidNotChange() {
        val decision = resolvePhotoRenderDecision(
            currentPath = PATH,
            currentStage = PhotoRenderStage.THUMBNAIL,
            currentContentVersion = VERSION_A,
            nextPath = PATH,
            nextContentVersion = VERSION_A,
            hasThumbnail = true,
            hasFull = true,
            isZoomed = true
        )

        assertEquals(PhotoRenderStage.FULL, decision.stage)
        assertFalse("the same version keeps the lazy upgrade", decision.shouldApplyImmediately)
    }

    @Test
    fun shouldKeepTheShownFull_whenTheSameVersionStateTemporarilyDropsIt() {
        val decision = resolvePhotoRenderDecision(
            currentPath = PATH,
            currentStage = PhotoRenderStage.FULL,
            currentContentVersion = VERSION_A,
            nextPath = PATH,
            nextContentVersion = VERSION_A,
            hasThumbnail = false,
            hasFull = false,
            isZoomed = false
        )

        assertEquals(PhotoRenderStage.FULL, decision.stage)
        assertFalse(decision.shouldApplyImmediately)
    }

    private companion object {
        const val PATH = "/photosync-test/version/photo.jpg"
        val VERSION_A: PhotoContentVersion = PhotoContentVersion.Edited("/edits/1/preview_a.jpg")
        val VERSION_B: PhotoContentVersion = PhotoContentVersion.Edited("/edits/1/preview_b.jpg")
    }
}
