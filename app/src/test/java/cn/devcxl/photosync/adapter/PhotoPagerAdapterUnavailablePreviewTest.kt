package cn.devcxl.photosync.adapter

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.entity.PhotoEntity
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView

import org.junit.Assert.assertEquals
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
 * Holder-level regression tests for photos whose edit record exists but cannot supply a preview.
 *
 * The pager must never fill such a page with the original file or a bitmap decoded before the
 * record changed, and it must not leave an endless loading spinner. These tests drive the real
 * [PhotoPagerAdapter] holder and inspect its real views.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoPagerAdapterUnavailablePreviewTest {

    private lateinit var parent: FrameLayout
    private lateinit var states: MutableMap<String, PhotoRenderState>
    private lateinit var createdTiledViews: MutableList<SubsamplingScaleImageView>
    private var jpegProvider: (String) -> Boolean = { false }
    private lateinit var adapter: PhotoPagerAdapter

    @Before
    fun setUp() {
        parent = FrameLayout(RuntimeEnvironment.getApplication())
        states = mutableMapOf()
        createdTiledViews = mutableListOf()
        jpegProvider = { false }
        adapter = createAdapter(JPEG_PATH, RAW_PATH)
    }

    @Test
    fun shouldClearThePreviousBitmapAndShowTheTerminalMessage_whenThePreviewDisappears() {
        val previous = bitmap(PREVIOUS_SIZE)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = null, full = previous)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val photoView = photoViewOf(holder)
        assertSame(previous, photoView.bitmap())

        states[JPEG_PATH] = unavailableState()
        adapter.onBindViewHolder(holder, 0)

        assertNull("the old bitmap must not survive the unavailable state", photoView.bitmap())
        assertEquals(View.VISIBLE, photoView.visibility)
        assertUnavailableMessage(holder)
    }

    @Test
    fun shouldNotKeepTheTiledOriginal_whenAnEditedJpegTurnsUnavailable() {
        jpegProvider = { path -> path == JPEG_PATH }
        adapter = createAdapter(JPEG_PATH, RAW_PATH)
        val preview = bitmap(PREVIEW_SIZE)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = preview, full = null)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val tiledView = tiledViewOf(holder)
        assertSame("the tiled viewer is attached before the record breaks", holder.itemView, tiledView.parent)
        assertTrue(tiledView.isReady)

        states[JPEG_PATH] = unavailableState()
        adapter.onBindViewHolder(holder, 0)

        assertNull("the tiled original must be detached", tiledView.parent)
        assertEquals("the bitmap surface takes over", View.VISIBLE, photoViewOf(holder).visibility)
        assertNull("the tiled original must not become a bitmap fallback", photoViewOf(holder).bitmap())
        assertUnavailableMessage(holder)
    }

    @Test
    fun shouldNotOpenTheTiledViewer_whenAProviderWronglyReportsJpegForAnUnavailablePreview() {
        jpegProvider = { true }

        val holder = adapter.onCreateViewHolder(parent, 0)
        states[JPEG_PATH] = unavailableState()
        adapter.onBindViewHolder(holder, 0)

        assertTrue(
            "an unavailable preview must never reach the URI-backed viewer: $createdTiledViews",
            createdTiledViews.isEmpty()
        )
        assertEquals(View.VISIBLE, photoViewOf(holder).visibility)
        assertNull(photoViewOf(holder).bitmap())
        assertUnavailableMessage(holder)
    }

    @Test
    fun shouldIgnoreATiledBind_whenTheRenderStateItselfIsUnavailable() {
        val holder = adapter.onCreateViewHolder(parent, 0) as PhotoPagerViewHolder

        holder.bind(
            path = JPEG_PATH,
            renderState = unavailableState(),
            overrideBitmap = null,
            viewerMode = PhotoViewerMode.JPEG_TILED,
            isCurrentPage = true,
            jpegSourceInfo = null,
            onPhotoScaleChanged = { _, _, _, _ -> },
            onPhotoTap = {}
        )

        assertTrue(
            "the holder must override a misrouted tiled bind: $createdTiledViews",
            createdTiledViews.isEmpty()
        )
        assertEquals(View.VISIBLE, photoViewOf(holder).visibility)
        assertNull(photoViewOf(holder).bitmap())
        assertUnavailableMessage(holder)
    }

    @Test
    fun shouldIgnoreBitmapsSuppliedAlongsideTheUnavailableFlag() {
        val stale = bitmap(PREVIOUS_SIZE)
        states[JPEG_PATH] = PhotoRenderState(
            thumbnail = stale,
            full = stale,
            isPreviewUnavailable = true
        )

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        assertNull(
            "the flag wins over stale cache bitmaps: never show the original",
            photoViewOf(holder).bitmap()
        )
        assertUnavailableMessage(holder)
    }

    @Test
    fun shouldShowTheUnavailableMessage_whenARawEditPreviewIsMissing() {
        states[RAW_PATH] = unavailableState()

        val holder = adapter.onCreateViewHolder(parent, 1)
        adapter.onBindViewHolder(holder, 1)

        assertNull(photoViewOf(holder).bitmap())
        assertUnavailableMessage(holder)
    }

    @Test
    fun shouldShowTheUnavailableMessage_whenAJpegEditPreviewIsMissing() {
        states[JPEG_PATH] = unavailableState()

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        assertNull(photoViewOf(holder).bitmap())
        assertUnavailableMessage(holder)
    }

    @Test
    fun shouldRecoverToTheEditedPreview_whenThePreviewBecomesAvailableAgain() {
        states[JPEG_PATH] = unavailableState()

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        assertUnavailableMessage(holder)

        val thumbnail = bitmap(THUMBNAIL_SIZE)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = thumbnail, full = null)
        adapter.onBindViewHolder(holder, 0)

        assertSame("the thumbnail must replace the message", thumbnail, photoViewOf(holder).bitmap())
        assertEquals(View.GONE, loadingOverlayOf(holder).visibility)

        val full = bitmap(FULL_SIZE)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = thumbnail, full = full)
        adapter.onBindViewHolder(holder, 0)

        assertSame("the full preview must replace the thumbnail", full, photoViewOf(holder).bitmap())
        assertEquals(View.GONE, loadingOverlayOf(holder).visibility)
    }

    @Test
    fun shouldDisarmTheComparisonOnly_whenTheComparedPageItselfTurnsUnavailable() {
        val graded = bitmap(FULL_SIZE)
        val original = bitmap(ORIGINAL_SIZE)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = null, full = graded)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        adapter.showImageOverride(JPEG_PATH, original)
        idleMainLooper()
        adapter.onBindViewHolder(holder, 0)
        assertSame("holding must show the original", original, photoViewOf(holder).bitmap())

        states[JPEG_PATH] = unavailableState()
        adapter.onBindViewHolder(holder, 0)

        assertNull("the page that went unavailable must drop its own override", adapter.overrideFor(JPEG_PATH))
        assertNull("the original must not survive the unavailable state", photoViewOf(holder).bitmap())
        assertUnavailableMessage(holder)

        // Recovery must not replay the disarmed original.
        states[JPEG_PATH] = PhotoRenderState(thumbnail = null, full = graded)
        adapter.onBindViewHolder(holder, 0)
        assertSame("the graded preview must come back", graded, photoViewOf(holder).bitmap())
    }

    @Test
    fun shouldKeepTheComparison_whenAnUnavailableNeighbourIsPreBound() {
        val graded = bitmap(FULL_SIZE)
        val original = bitmap(ORIGINAL_SIZE)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = null, full = graded)

        val compared = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(compared, 0)
        assertSame(graded, photoViewOf(compared).bitmap())

        adapter.showImageOverride(JPEG_PATH, original)
        idleMainLooper()
        adapter.onBindViewHolder(compared, 0)
        assertSame("holding must show the original", original, photoViewOf(compared).bitmap())

        // RecyclerView pre-binds the next page off screen while the finger is still down.
        states[RAW_PATH] = unavailableState()
        val neighbour = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(neighbour, 1)
        assertUnavailableMessage(neighbour)

        assertSame(
            "an unavailable neighbour must not disarm another page's comparison",
            original,
            adapter.overrideFor(JPEG_PATH)
        )
        adapter.onBindViewHolder(compared, 0)
        assertSame(
            "the compared page must still render its original after the neighbour bind",
            original,
            photoViewOf(compared).bitmap()
        )
    }

    private fun idleMainLooper() {
        ShadowLooper.shadowMainLooper().idle()
    }

    private fun createAdapter(vararg paths: String): PhotoPagerAdapter {
        val created = PhotoPagerAdapter(
            renderStateProvider = { path ->
                states[path] ?: PhotoRenderState(thumbnail = null, full = null)
            },
            onBindRequest = { _, _ -> },
            jpegSourceInfoProvider = { null },
            isCurrentPageProvider = { false },
            isJpegProvider = { path -> jpegProvider(path) },
            onPhotoScaleChanged = { _, _, _, _ -> },
            onPhotoTap = {},
            configureTiledViewer = { view ->
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
                createdTiledViews += view
            }
        )
        created.updateItems(paths.mapIndexed { index, path -> PhotoEntity(id = index + 1L, path = path) })
        ShadowLooper.shadowMainLooper().idle()
        return created
    }

    private fun unavailableState(): PhotoRenderState =
        PhotoRenderState(thumbnail = null, full = null, isPreviewUnavailable = true)

    private fun assertUnavailableMessage(holder: RecyclerView.ViewHolder) {
        val overlay = loadingOverlayOf(holder)
        assertEquals("the recovery message must be visible", View.VISIBLE, overlay.visibility)
        val spinner = overlay as ViewGroup
        assertEquals(
            "a terminal state must not look like it is still loading",
            View.GONE,
            (spinner.getChildAt(0) as ProgressBar).visibility
        )
        assertEquals(
            RuntimeEnvironment.getApplication().getString(R.string.edited_preview_unavailable),
            (spinner.getChildAt(1) as TextView).text.toString()
        )
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
        const val JPEG_PATH = "/photosync-test/unavailable/photo.jpg"
        const val RAW_PATH = "/photosync-test/unavailable/photo.dng"
        const val PREVIOUS_SIZE = 90
        const val PREVIEW_SIZE = 60
        const val THUMBNAIL_SIZE = 40
        const val FULL_SIZE = 120
        const val ORIGINAL_SIZE = 70
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
    }
}
