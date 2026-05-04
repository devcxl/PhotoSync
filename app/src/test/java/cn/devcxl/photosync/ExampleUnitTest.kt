package cn.devcxl.photosync.activity

import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.adapter.PhotoRenderStage
import cn.devcxl.photosync.adapter.PhotoViewerMode
import cn.devcxl.photosync.adapter.JpegTiledSourceMode
import cn.devcxl.photosync.adapter.resolveJpegTiledBindDecision
import cn.devcxl.photosync.adapter.resolveJpegTiledSourceMode
import cn.devcxl.photosync.adapter.resolvePhotoViewerMode
import cn.devcxl.photosync.adapter.resolvePhotoRenderDecision
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ExampleUnitTest {
    @Test
    fun shouldUseTiledViewer_whenIsJpegAndUsePhotoViewOtherwise() {
        assertEquals(
            PhotoViewerMode.JPEG_TILED,
            resolvePhotoViewerMode(isCurrentPage = true, isJpeg = true)
        )
        assertEquals(
            PhotoViewerMode.JPEG_TILED,
            resolvePhotoViewerMode(isCurrentPage = false, isJpeg = true)
        )
        assertEquals(
            PhotoViewerMode.PHOTO_VIEW,
            resolvePhotoViewerMode(isCurrentPage = true, isJpeg = false)
        )
    }

    @Test
    fun shouldUseBitmapOnly_whenNotCurrentPage() {
        val mode = resolveJpegTiledSourceMode(
            isCurrentPage = false,
            hasPreviewBitmap = true
        )

        assertEquals("BITMAP_ONLY", mode.name)
    }

    @Test
    fun shouldUseTiledWithPreview_whenCurrentPageAndHasPreview() {
        val mode = resolveJpegTiledSourceMode(
            isCurrentPage = true,
            hasPreviewBitmap = true
        )

        assertEquals("TILED_WITH_PREVIEW", mode.name)
    }

    @Test
    fun shouldLoadPreview_returnsTrueForCurrentPageEvenWhilePagerIsSettling() {
        assertTrue(shouldLoadPreview(isCurrentPage = true))
    }

    @Test
    fun shouldLoadPreview_returnsFalseForNonCurrentPage() {
        assertFalse(shouldLoadPreview(isCurrentPage = false))
    }

    @Test
    fun shouldReturnPowerOfTwo_whenImageIsLarge() {
        val result = calculateInSampleSize(
            srcWidth = 6000,
            srcHeight = 4000,
            reqWidth = 1500,
            reqHeight = 1000
        )

        assertEquals(4, result)
    }

    @Test
    fun shouldReturnOne_whenSourceIsAlreadySmall() {
        val result = calculateInSampleSize(
            srcWidth = 800,
            srcHeight = 600,
            reqWidth = 1600,
            reqHeight = 1200
        )

        assertEquals(1, result)
    }

    @Test
    fun shouldBlockDowngrade_whenCurrentIsFullAndNextIsThumbnail() {
        val decision = resolvePhotoRenderDecision(
            currentPath = "/tmp/a.jpg",
            currentStage = PhotoRenderStage.FULL,
            nextPath = "/tmp/a.jpg",
            hasThumbnail = true,
            hasFull = false,
            isZoomed = false
        )

        assertEquals(PhotoRenderStage.FULL, decision.stage)
        assertFalse(decision.shouldApplyImmediately)
    }

    @Test
    fun shouldDeferUpgrade_whenZoomed() {
        val decision = resolvePhotoRenderDecision(
            currentPath = "/tmp/a.jpg",
            currentStage = PhotoRenderStage.THUMBNAIL,
            nextPath = "/tmp/a.jpg",
            hasThumbnail = true,
            hasFull = true,
            isZoomed = true
        )

        assertEquals(PhotoRenderStage.FULL, decision.stage)
        assertFalse(decision.shouldApplyImmediately)
    }

    @Test
    fun shouldIgnoreRebind_whenSameStage() {
        val decision = resolvePhotoRenderDecision(
            currentPath = "/tmp/a.jpg",
            currentStage = PhotoRenderStage.THUMBNAIL,
            nextPath = "/tmp/a.jpg",
            hasThumbnail = true,
            hasFull = false,
            isZoomed = false
        )

        assertEquals(PhotoRenderStage.THUMBNAIL, decision.stage)
        assertFalse(decision.shouldApplyImmediately)
    }

    @Test
    fun shouldRebind_whenUpgradeBeforeViewerReady() {
        val decision = resolveJpegTiledBindDecision(
            currentPath = "/tmp/a.jpg",
            currentStage = PhotoRenderStage.THUMBNAIL,
            currentSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            nextPath = "/tmp/a.jpg",
            nextStage = PhotoRenderStage.FULL,
            nextSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            isViewerReady = false
        )

        assertTrue(decision.shouldSetImage)
        assertEquals(PhotoRenderStage.FULL, decision.stage)
    }

    @Test
    fun shouldSkipReset_whenViewerReady() {
        val decision = resolveJpegTiledBindDecision(
            currentPath = "/tmp/a.jpg",
            currentStage = PhotoRenderStage.THUMBNAIL,
            currentSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            nextPath = "/tmp/a.jpg",
            nextStage = PhotoRenderStage.FULL,
            nextSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            isViewerReady = true
        )

        assertFalse(decision.shouldSetImage)
        assertEquals(PhotoRenderStage.FULL, decision.stage)
    }

    @Test
    fun shouldRebind_whenSourceModeChanges() {
        val decision = resolveJpegTiledBindDecision(
            currentPath = "/tmp/a.jpg",
            currentStage = PhotoRenderStage.THUMBNAIL,
            currentSourceMode = JpegTiledSourceMode.BITMAP_ONLY,
            nextPath = "/tmp/a.jpg",
            nextStage = PhotoRenderStage.THUMBNAIL,
            nextSourceMode = JpegTiledSourceMode.TILED_WITH_PREVIEW,
            isViewerReady = true
        )

        assertTrue(decision.shouldSetImage)
        assertEquals(PhotoRenderStage.THUMBNAIL, decision.stage)
    }

    @Test
    fun shouldReturnTrue_whenScaleAboveTolerance() {
        assertFalse(PhotoPagerAdapter.VH.testIsZoomed(scale = 1.005f, minScale = 1.0f))
        assertTrue(PhotoPagerAdapter.VH.testIsZoomed(scale = 1.05f, minScale = 1.0f))
    }
}
