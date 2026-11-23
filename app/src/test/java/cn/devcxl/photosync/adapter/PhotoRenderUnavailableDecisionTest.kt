package cn.devcxl.photosync.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decisions for the "edit record exists but its preview is gone" state.
 *
 * The adapter must clear whatever it showed, must not draw a loading spinner, and must not route
 * the page to the URI-backed tiled viewer, even when the JPEG provider asks for it.
 */
class PhotoRenderUnavailableDecisionTest {

    @Test
    fun shouldForceAnImmediateClear_whenThePreviewIsUnavailable() {
        val decision = resolvePhotoRenderDecision(
            currentPath = "/photos/photo.jpg",
            currentStage = PhotoRenderStage.FULL,
            currentContentVersion = PhotoContentVersion.Unspecified,
            nextPath = "/photos/photo.jpg",
            nextContentVersion = PhotoContentVersion.Unspecified,
            hasThumbnail = false,
            hasFull = false,
            isZoomed = false,
            hasPreviewUnavailable = true
        )

        assertEquals(PhotoRenderStage.UNAVAILABLE, decision.stage)
        assertTrue("a stale bitmap or override must be cleared on the same bind", decision.shouldApplyImmediately)
    }

    @Test
    fun shouldNotShowTheLoadingSpinner_whenTheStageIsUnavailable() {
        assertFalse(
            resolveLoadingOverlayVisible(
                stage = PhotoRenderStage.UNAVAILABLE,
                isJpeg = false,
                isTiledViewerReady = false
            )
        )
        assertFalse(
            resolveLoadingOverlayVisible(
                stage = PhotoRenderStage.UNAVAILABLE,
                isJpeg = true,
                isTiledViewerReady = false
            )
        )
    }

    @Test
    fun shouldUseTheBitmapViewer_whenThePreviewIsUnavailable() {
        assertEquals(
            PhotoViewerMode.PHOTO_VIEW,
            resolvePhotoViewerMode(isJpeg = true, isPreviewUnavailable = true)
        )
        assertEquals(
            PhotoViewerMode.JPEG_TILED,
            resolvePhotoViewerMode(isJpeg = true, isPreviewUnavailable = false)
        )
    }

    @Test
    fun shouldResolveTheUnavailableJpegStage() {
        assertEquals(
            PhotoRenderStage.UNAVAILABLE,
            resolveJpegPreviewStage(
                PhotoRenderState(
                    thumbnail = null,
                    full = null,
                    isPreviewUnavailable = true
                )
            )
        )
    }
}
