package cn.devcxl.photosync.activity

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the token ordering behind an unavailable edit preview.
 *
 * A decode that started while the page still read the original must not publish after the record
 * turns unavailable. Otherwise the original would sit in the cache and be shown as the graded
 * preview once the preview file recovers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PreviewCacheDiscardTest {

    private val thumbnailCache = LruCache<String, Bitmap>(4)
    private val fullCache = LruCache<String, Bitmap>(2)
    private val thumbnailDecodes = PreviewTaskRegistry()
    private val fullPreviewDecodes = PreviewTaskRegistry()

    @Test
    fun shouldRejectLateOriginalAndAcceptRecoveredPreview_whenUnavailableDiscardRuns() {
        val originalThumbnail = bitmap(THUMBNAIL_SIZE)
        val originalFull = bitmap(FULL_SIZE)
        thumbnailCache.put(PATH, originalThumbnail)
        fullCache.put(PATH, originalFull)
        val originalThumbnailTask = thumbnailDecodes.begin(PATH)!!
        val originalFullTask = fullPreviewDecodes.begin(PATH)!!

        // The record turns unavailable: both in-flight original decodes are revoked and both
        // caches are cleared.
        discardPreviewArtifacts(
            path = PATH,
            thumbnailDecodes = thumbnailDecodes,
            fullPreviewDecodes = fullPreviewDecodes,
            thumbnailCache = thumbnailCache,
            fullCache = fullCache
        )

        assertNull("a cached original thumbnail must be dropped", thumbnailCache.get(PATH))
        assertNull("a cached original full image must be dropped", fullCache.get(PATH))

        // The original decodes finish late. Their tokens are stale, so neither can write back.
        assertFalse(
            thumbnailDecodes.publishIfCurrent(originalThumbnailTask) {
                thumbnailCache.put(PATH, originalThumbnail)
            }
        )
        assertFalse(
            fullPreviewDecodes.publishIfCurrent(originalFullTask) {
                fullCache.put(PATH, originalFull)
            }
        )
        assertNull("the late original thumbnail must not reappear", thumbnailCache.get(PATH))
        assertNull("the late original full image must not reappear", fullCache.get(PATH))

        // The preview file becomes readable again: a fresh decode owns the path.
        val editedThumbnail = bitmap(THUMBNAIL_SIZE + 8)
        val editedFull = bitmap(FULL_SIZE + 8)
        val newThumbnailTask = thumbnailDecodes.begin(PATH)!!
        val newFullTask = fullPreviewDecodes.begin(PATH)!!

        // A stale release must not hide the recovered decode's ownership.
        thumbnailDecodes.release(originalThumbnailTask)
        fullPreviewDecodes.release(originalFullTask)
        assertNull("the recovered thumbnail decode still owns the path", thumbnailDecodes.begin(PATH))
        assertNull("the recovered full decode still owns the path", fullPreviewDecodes.begin(PATH))

        assertTrue(
            thumbnailDecodes.publishIfCurrent(newThumbnailTask) {
                thumbnailCache.put(PATH, editedThumbnail)
            }
        )
        assertTrue(
            fullPreviewDecodes.publishIfCurrent(newFullTask) {
                fullCache.put(PATH, editedFull)
            }
        )
        assertSame("only the recovered preview may be shown", editedThumbnail, thumbnailCache.get(PATH))
        assertSame(editedFull, fullCache.get(PATH))
    }

    private fun bitmap(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private companion object {
        const val PATH = "/cache/raw/DSC04821.ARW"
        const val THUMBNAIL_SIZE = 40
        const val FULL_SIZE = 80
    }
}
