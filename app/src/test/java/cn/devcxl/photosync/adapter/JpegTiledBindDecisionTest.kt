package cn.devcxl.photosync.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bind decisions the pager relies on around tiled-viewer rebinds.
 *
 * The adapter combines these decisions with [TiledViewerSlot]: whenever the decision asks for a
 * new image while the view is still busy, the slot binds a replacement view instead of mutating
 * the old one.
 */
class JpegTiledBindDecisionTest {

    @Test
    fun shouldRequestImage_whenTheViewWasResetForTheSamePath() {
        // A recycled or replaced view reports no path and is not ready, so the same photo must be
        // applied again rather than treated as already displayed.
        val decision = resolveJpegTiledBindDecision(
            currentPath = null,
            currentStage = PhotoRenderStage.EMPTY,
            currentSourceMode = null,
            nextPath = PHOTO_PATH,
            nextStage = PhotoRenderStage.FULL,
            nextSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            isViewerReady = false
        )

        assertTrue(decision.shouldSetImage)
        assertEquals(PhotoRenderStage.FULL, decision.stage)
    }

    @Test
    fun shouldNotRequestImage_whenPathStageAndModeAreUnchanged() {
        val decision = resolveJpegTiledBindDecision(
            currentPath = PHOTO_PATH,
            currentStage = PhotoRenderStage.FULL,
            currentSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            nextPath = PHOTO_PATH,
            nextStage = PhotoRenderStage.FULL,
            nextSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            isViewerReady = true
        )

        assertFalse("an idle, fully rendered view must be reused as is", decision.shouldSetImage)
    }

    @Test
    fun shouldRequestImage_whenThePreviewStageAdvancesBeforeTheViewIsReady() {
        // This is the rebind that used to call setImage while the previous decode was still in
        // flight for the very same view.
        val decision = resolveJpegTiledBindDecision(
            currentPath = PHOTO_PATH,
            currentStage = PhotoRenderStage.THUMBNAIL,
            currentSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            nextPath = PHOTO_PATH,
            nextStage = PhotoRenderStage.FULL,
            nextSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            isViewerReady = false
        )

        assertTrue(decision.shouldSetImage)
        assertEquals(PhotoRenderStage.FULL, decision.stage)
    }

    @Test
    fun shouldRequestImage_whenThePageStopsBeingCurrent() {
        val decision = resolveJpegTiledBindDecision(
            currentPath = PHOTO_PATH,
            currentStage = PhotoRenderStage.FULL,
            currentSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            nextPath = PHOTO_PATH,
            nextStage = PhotoRenderStage.FULL,
            nextSourceMode = JpegTiledSourceMode.BITMAP_ONLY,
            isViewerReady = true
        )

        assertTrue("a changed source mode must re-apply the image", decision.shouldSetImage)
    }

    @Test
    fun shouldKeepFullStage_whenTheFullPreviewDisappears() {
        // Never downgrade a full preview back to a thumbnail while it is still on screen.
        val decision = resolveJpegTiledBindDecision(
            currentPath = PHOTO_PATH,
            currentStage = PhotoRenderStage.FULL,
            currentSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            nextPath = PHOTO_PATH,
            nextStage = PhotoRenderStage.THUMBNAIL,
            nextSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            isViewerReady = true
        )

        assertFalse(decision.shouldSetImage)
        assertEquals(PhotoRenderStage.FULL, decision.stage)
    }

    private companion object {
        const val PHOTO_PATH = "/storage/emulated/0/DCIM/photo.jpg"
    }
}
