package cn.devcxl.photosync.adapter

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar

import androidx.recyclerview.widget.RecyclerView

import cn.devcxl.photosync.data.entity.PhotoEntity
import com.github.chrisbanes.photoview.OnScaleChangedListener
import com.github.chrisbanes.photoview.PhotoView
import com.github.chrisbanes.photoview.PhotoViewAttacher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Holder-level regression tests for a same-path PhotoView content update.
 *
 * Saving a new edit replaces the rendered preview of a file the page already shows. Everything
 * decoded for the previous record is stale then: the drawable on screen and any full frame that
 * the zoom gesture deferred must both go, and the next state (empty, thumbnail, full) must be
 * applied as it arrives. Within one version the lazy thumbnail-to-full upgrade stays untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoPagerContentVersionTest {

    private lateinit var parent: FrameLayout
    private lateinit var states: MutableMap<String, PhotoRenderState>
    private lateinit var adapter: PhotoPagerAdapter
    private lateinit var holder: PhotoPagerViewHolder

    @Before
    fun setUp() {
        parent = FrameLayout(RuntimeEnvironment.getApplication())
        states = mutableMapOf()
        adapter = PhotoPagerAdapter(
            renderStateProvider = { path ->
                states[path] ?: PhotoRenderState(thumbnail = null, full = null)
            },
            onBindRequest = { _, _ -> },
            jpegSourceInfoProvider = { null },
            isCurrentPageProvider = { false },
            // Keep every page on the bitmap surface: this defect is specific to PHOTO_VIEW.
            isJpegProvider = { false },
            onPhotoScaleChanged = { _, _, _, _ -> },
            onPhotoTap = {}
        )
        adapter.updateItems(listOf(PhotoEntity(id = 1L, path = PHOTO_PATH)))
    }

    @Test
    fun shouldWalkFromTheOldEditedFrameThroughEmptyAndThumbnailToTheNewFull() {
        val oldFrame = bitmap(OLD_FRAME_SIZE)
        bind(state = PhotoRenderState(thumbnail = null, full = oldFrame, contentVersion = VERSION_A))

        val photoView = photoViewOf(holder)
        assertSame("the first render must show the edited full", oldFrame, photoView.bitmap())

        // The edit was re-saved and its new preview is not decoded yet.
        bind(state = PhotoRenderState(thumbnail = null, full = null, contentVersion = VERSION_B))
        assertNull("the previous version's frame must not survive an empty state", photoView.bitmap())
        assertLoading(holder)

        val newThumbnail = bitmap(THUMBNAIL_SIZE)
        bind(state = PhotoRenderState(thumbnail = newThumbnail, full = null, contentVersion = VERSION_B))
        assertSame("the new thumbnail must apply after the empty bind", newThumbnail, photoView.bitmap())
        assertEquals("a shown bitmap must hide the loading pill", View.GONE, loadingOverlayOf(holder).visibility)
        assertNotSame("the old edited frame must never come back", oldFrame, photoView.bitmap())

        val newFull = bitmap(NEW_FULL_SIZE)
        bind(state = PhotoRenderState(thumbnail = newThumbnail, full = newFull, contentVersion = VERSION_B))
        assertSame("the new full must replace the thumbnail", newFull, photoView.bitmap())
        assertNotSame("the old edited frame must never come back", oldFrame, photoView.bitmap())
    }

    @Test
    fun shouldDropTheDeferredOldFull_whenTheVersionChangesAndTheViewZoomsBackOut() {
        val oldThumbnail = bitmap(THUMBNAIL_SIZE)
        val oldFull = bitmap(OLD_FRAME_SIZE)
        bind(
            state = PhotoRenderState(
                thumbnail = oldThumbnail,
                full = null,
                contentVersion = VERSION_A
            )
        )
        assertSame(oldThumbnail, photoViewOf(holder).bitmap())

        // Zoomed in, the full frame of the current version is deferred instead of swapping
        // the bitmap under the gesture.
        val photoView = photoViewOf(holder)
        photoView.scale = ZOOMED_SCALE
        bind(
            state = PhotoRenderState(
                thumbnail = oldThumbnail,
                full = oldFull,
                contentVersion = VERSION_A
            )
        )
        assertSame("the same version keeps the deferred upgrade", oldThumbnail, photoView.bitmap())

        // A new edit lands while still zoomed. Its state has no full yet, but the deferred old
        // frame and the old thumbnail are both stale.
        val newThumbnail = bitmap(THUMBNAIL_SIZE + 8)
        bind(
            state = PhotoRenderState(
                thumbnail = newThumbnail,
                full = null,
                contentVersion = VERSION_B
            )
        )
        assertSame(
            "the new version must replace the old bitmap even while zoomed",
            newThumbnail,
            photoView.bitmap()
        )

        // Zooming back out runs the pending-apply callback; the deferred old full must be gone.
        photoView.scale = MIN_SCALE
        dispatchScaleChanged(photoView, MIN_SCALE)
        assertSame(
            "the pending frame of the previous version must not be restored",
            newThumbnail,
            photoView.bitmap()
        )
        assertNotSame(oldFull, photoView.bitmap())
    }

    @Test
    fun shouldApplyTheNewVersionFullImmediately_whenTheViewIsStillZoomed() {
        val oldThumbnail = bitmap(THUMBNAIL_SIZE)
        bind(
            state = PhotoRenderState(
                thumbnail = oldThumbnail,
                full = null,
                contentVersion = VERSION_A
            )
        )
        val photoView = photoViewOf(holder)
        photoView.scale = ZOOMED_SCALE

        val newFull = bitmap(NEW_FULL_SIZE)
        bind(
            state = PhotoRenderState(
                thumbnail = oldThumbnail,
                full = newFull,
                contentVersion = VERSION_B
            )
        )

        assertSame(
            "a full of the new version must not wait for the zoom gesture to end",
            newFull,
            photoView.bitmap()
        )
        assertEquals(View.GONE, loadingOverlayOf(holder).visibility)
    }

    private fun bind(state: PhotoRenderState) {
        states[PHOTO_PATH] = state
        if (!::holder.isInitialized) {
            holder = adapter.onCreateViewHolder(parent, 0)
        }
        adapter.onBindViewHolder(holder, 0)
    }

    private fun assertLoading(holder: RecyclerView.ViewHolder) {
        val overlay = loadingOverlayOf(holder)
        assertEquals("an empty new version must look like it is loading", View.VISIBLE, overlay.visibility)
        assertTrue(
            "the pill must keep its spinner while the new preview decodes",
            (overlay as ViewGroup).getChildAt(0) is ProgressBar &&
                overlay.getChildAt(0).visibility == View.VISIBLE
        )
    }

    /**
     * Runs the scale-changed callback the holder registered on [photoView].
     *
     * Robolectric cannot synthesize PhotoView's custom multi-touch gestures reliably, so this
     * invokes the exact listener instance stored by `setOnScaleChangeListener`.
     */
    private fun dispatchScaleChanged(photoView: PhotoView, scale: Float) {
        val field = PhotoViewAttacher::class.java.getDeclaredField("mScaleChangeListener")
        field.isAccessible = true
        val listener = field.get(photoView.attacher) as OnScaleChangedListener
        listener.onScaleChange(scale, 0f, 0f)
    }

    private fun photoViewOf(holder: RecyclerView.ViewHolder): PhotoView {
        return (holder.itemView as ViewGroup).getChildAt(0) as PhotoView
    }

    private fun loadingOverlayOf(holder: RecyclerView.ViewHolder): View {
        val container = holder.itemView as ViewGroup
        return container.getChildAt(container.childCount - 1)
    }

    private fun PhotoView.bitmap(): Bitmap? {
        return (drawable as? BitmapDrawable)?.bitmap
    }

    private fun bitmap(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private companion object {
        const val PHOTO_PATH = "/photosync-test/content-version/photo.jpg"
        const val OLD_FRAME_SIZE = 120
        const val THUMBNAIL_SIZE = 40
        const val NEW_FULL_SIZE = 160
        const val MIN_SCALE = 1.0f
        const val ZOOMED_SCALE = 2.0f
        val VERSION_A: PhotoContentVersion = PhotoContentVersion.Edited("/edits/1/preview_a.jpg")
        val VERSION_B: PhotoContentVersion = PhotoContentVersion.Edited("/edits/1/preview_b.jpg")
    }
}
