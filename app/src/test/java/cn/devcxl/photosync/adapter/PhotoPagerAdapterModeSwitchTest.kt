package cn.devcxl.photosync.adapter

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import cn.devcxl.photosync.data.entity.PhotoEntity
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
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
import org.robolectric.shadows.ShadowLooper

/**
 * Holder-level regression tests for the viewer-mode transitions of [PhotoPagerAdapter].
 *
 * The interesting production sequence is an edit preview appearing for a path that the tiled JPEG
 * viewer showed a moment ago, and the press-and-hold "Before" comparison ending while the graded
 * preview cache is still empty. In both cases the tracked stage belongs to another surface than
 * the PhotoView, so trusting it would either skip the bind (stale drawable, hidden pill) or keep
 * the original pixels after the edit is active.
 *
 * These tests drive the real holder and inspect the real child views; only the row data is faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoPagerAdapterModeSwitchTest {

    private lateinit var parent: FrameLayout
    private lateinit var states: MutableMap<String, PhotoRenderState>
    private var edited: Boolean = false
    private lateinit var adapter: PhotoPagerAdapter

    @Before
    fun setUp() {
        parent = FrameLayout(RuntimeEnvironment.getApplication())
        states = mutableMapOf()
        edited = false
        adapter = PhotoPagerAdapter(
            renderStateProvider = { path -> states[path] ?: PhotoRenderState(thumbnail = null, full = null) },
            onBindRequest = { _, _ -> },
            jpegSourceInfoProvider = { null },
            isCurrentPageProvider = { false },
            isJpegProvider = { path -> path == PHOTO_PATH && !edited },
            onPhotoScaleChanged = { _, _, _, _ -> },
            onPhotoTap = {},
            // The holder is never laid out by a real RecyclerView here; a measured tiled view is
            // required for SSIV to report readiness.
            configureTiledViewer = { view ->
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
            }
        )
        adapter.updateItems(listOf(PhotoEntity(id = 1, path = PHOTO_PATH)))
    }

    @Test
    fun shouldNotKeepTheTiledStageOrTheOriginal_whenAnEditAppearsWithAnEmptyCache() {
        val gradedFull = bitmap(GRADED_SIZE)
        val original = bitmap(ORIGINAL_SIZE)
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = null, full = gradedFull)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val tiledView = tiledViewOf(holder)
        assertTrue("the JPEG path must start in the tiled viewer", tiledView.isReady)

        // Press-and-hold shows the original, then releasing returns to the tiled viewer.
        adapter.showImageOverride(PHOTO_PATH, original)
        idleMainLooper()
        adapter.onBindViewHolder(holder, 0)
        assertSame(original, photoViewOf(holder).bitmap())

        adapter.clearImageOverride()
        idleMainLooper()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, photoViewOf(holder).visibility)

        // The edit preview exists now, but nothing graded is cached yet.
        edited = true
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = null, full = null)
        adapter.onBindViewHolder(holder, 0)

        val photoView = photoViewOf(holder)
        assertEquals(View.VISIBLE, photoView.visibility)
        assertNull("the PhotoView must drop the original compare frame", photoView.bitmap())
        assertEquals(
            "an empty graded cache must show the loading pill",
            View.VISIBLE,
            loadingOverlayOf(holder).visibility
        )
        assertNull("the tiled viewer must be released when the edit takes over", tiledView.parent)

        // Retries: the thumbnail then the graded full must land without ever showing the original.
        val thumbnail = bitmap(THUMBNAIL_SIZE)
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = thumbnail, full = null)
        adapter.onBindViewHolder(holder, 0)
        assertSame("the late thumbnail must replace the empty state", thumbnail, photoView.bitmap())
        assertEquals(View.GONE, loadingOverlayOf(holder).visibility)

        val editedFull = bitmap(GRADED_SIZE)
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = thumbnail, full = editedFull)
        adapter.onBindViewHolder(holder, 0)
        assertSame("the graded full must replace the thumbnail", editedFull, photoView.bitmap())
        assertNotSame("the original compare frame must never come back", original, photoView.bitmap())
        assertNotSame("the pre-edit preview must not stay on screen", gradedFull, photoView.bitmap())
    }

    @Test
    fun shouldClearTheOverrideAndShowLoading_whenTheComparisonEndsWithAnEmptyCache() {
        val graded = bitmap(GRADED_SIZE)
        val original = bitmap(ORIGINAL_SIZE)
        edited = true
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = null, full = graded)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val photoView = photoViewOf(holder)
        assertSame(graded, photoView.bitmap())

        adapter.showImageOverride(PHOTO_PATH, original)
        idleMainLooper()
        adapter.onBindViewHolder(holder, 0)
        assertSame("holding must show the original", original, photoView.bitmap())

        // A bind while the finger is still down (e.g. a payload update) must keep the comparison.
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = null, full = null)
        adapter.onBindViewHolder(holder, 0)
        assertSame("the comparison must survive a bind with an empty cache", original, photoView.bitmap())

        adapter.clearImageOverride()
        idleMainLooper()
        adapter.onBindViewHolder(holder, 0)

        assertNull("releasing must clear the original pixels", photoView.bitmap())
        assertEquals(
            "releasing onto an empty cache must show the loading pill",
            View.VISIBLE,
            loadingOverlayOf(holder).visibility
        )

        val thumbnail = bitmap(THUMBNAIL_SIZE)
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = thumbnail, full = null)
        adapter.onBindViewHolder(holder, 0)
        assertSame("the thumbnail must apply after the empty bind", thumbnail, photoView.bitmap())
        assertEquals(View.GONE, loadingOverlayOf(holder).visibility)

        val editedFull = bitmap(GRADED_SIZE)
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = thumbnail, full = editedFull)
        adapter.onBindViewHolder(holder, 0)
        assertSame("the full preview must apply after the thumbnail", editedFull, photoView.bitmap())
        assertNotSame("the edit must never fall back to the original", original, photoView.bitmap())
    }

    @Test
    fun shouldApplyTheOnlyThumbnail_whenAZoomedPhotoViewReturnsFromTheTiledViewer() {
        val graded = bitmap(GRADED_SIZE)
        edited = true
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = null, full = graded)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val photoView = photoViewOf(holder)
        assertSame(graded, photoView.bitmap())

        // Back to the JPEG viewer (edit reverted), with the PhotoView hidden but still zoomed.
        edited = false
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, photoView.visibility)
        assertTrue(tiledViewOf(holder).isReady)
        photoView.scale = 2.0f
        assertTrue("the hidden PhotoView keeps its zoom", photoView.scale > photoView.minimumScale)

        // The edit comes back while only the thumbnail is cached.
        edited = true
        val thumbnail = bitmap(THUMBNAIL_SIZE)
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = thumbnail, full = null)
        adapter.onBindViewHolder(holder, 0)

        assertEquals(View.VISIBLE, photoView.visibility)
        assertSame(
            "the only available thumbnail must be applied instead of keeping the old bitmap",
            thumbnail,
            photoView.bitmap()
        )
        assertEquals(View.GONE, loadingOverlayOf(holder).visibility)
    }

    @Test
    fun shouldKeepTheComparedOriginal_whenAZoomCallbackFiresWithAPendingFull() {
        val fullA = bitmap(GRADED_SIZE)
        val fullB = bitmap(GRADED_SIZE + 16)
        val original = bitmap(ORIGINAL_SIZE)
        edited = true
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = null, full = fullA)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val photoView = photoViewOf(holder)
        assertSame(fullA, photoView.bitmap())

        // A zoomed PhotoView defers a new full instead of swapping it under the gesture.
        photoView.scale = 2.0f
        states[PHOTO_PATH] = PhotoRenderState(thumbnail = null, full = fullB)
        adapter.onBindViewHolder(holder, 0)
        assertSame("the new full must wait while zoomed", fullA, photoView.bitmap())

        adapter.showImageOverride(PHOTO_PATH, original)
        idleMainLooper()
        adapter.onBindViewHolder(holder, 0)
        assertSame("holding must show the original", original, photoView.bitmap())

        // The override bitmap resets the zoom, which would let a pending apply through.
        photoView.scale = 1.0f
        dispatchScaleChanged(photoView, 1.5f)

        assertSame(
            "a scale callback must not apply the pending full over the compared original",
            original,
            photoView.bitmap()
        )

        adapter.clearImageOverride()
        idleMainLooper()
        adapter.onBindViewHolder(holder, 0)

        assertSame("releasing must apply the deferred full", fullB, photoView.bitmap())
        assertNotSame(original, photoView.bitmap())
    }

    private fun idleMainLooper() {
        ShadowLooper.shadowMainLooper().idle()
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

    private fun tiledViewOf(holder: RecyclerView.ViewHolder): SubsamplingScaleImageView {
        val container = holder.itemView as FrameLayout
        return container.getChildAt(1) as SubsamplingScaleImageView
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
        const val PHOTO_PATH = "/photosync-test/mode-switch/photo.jpg"
        const val GRADED_SIZE = 120
        const val THUMBNAIL_SIZE = 40
        const val ORIGINAL_SIZE = 60
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
    }
}
