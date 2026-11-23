package cn.devcxl.photosync.adapter

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import cn.devcxl.photosync.data.entity.PhotoEntity
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.github.chrisbanes.photoview.PhotoView
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Binding-level regression tests for the pager's tiled viewer.
 *
 * These go through the real [PhotoPagerAdapter] and its holder: the adapter's decode worker is a
 * manual executor and the main-thread sentinels go through a normal [Handler], so a stale
 * `TilesInitTask` result can be released at exactly the moment the holder is rebound. The
 * assertions check the holder's actual views, not the slot model in isolation.
 *
 * The decoder doubles are local to this file on purpose: the other adapter tests own their own
 * fakes, and these three cases only need a region decoder that reports a fixed size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoPagerAdapterTiledBindTest {

    private lateinit var parent: FrameLayout
    private lateinit var worker: AdapterManualExecutor
    private lateinit var regionFactory: AdapterFakeRegionDecoderFactory
    private lateinit var bitmapFactory: AdapterFakeBitmapDecoderFactory
    private lateinit var states: MutableMap<String, PhotoRenderState>
    private lateinit var createdViews: MutableList<SubsamplingScaleImageView>
    private var currentPage: Int = RecyclerView.NO_POSITION
    private lateinit var adapter: PhotoPagerAdapter

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        parent = FrameLayout(app)
        worker = AdapterManualExecutor()
        regionFactory = AdapterFakeRegionDecoderFactory(URI_WIDTH, URI_HEIGHT)
        bitmapFactory = AdapterFakeBitmapDecoderFactory(CACHED_SIZE)
        states = mutableMapOf()
        createdViews = mutableListOf()
        currentPage = RecyclerView.NO_POSITION
        adapter = createAdapter(JPEG_PATH)
    }

    @Test
    fun shouldKeepBusyUriResultOnTheDetachedView_whenTheBindNeedsANonCurrentBitmap() {
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        assertEquals("the off-screen URI bind must start one tiled decode", 1, worker.pendingCount)
        val staleView = createdViews.single()
        assertSame(holder.itemView, staleView.parent)
        assertFalse(staleView.isReady)

        val thumbnail = Bitmap.createBitmap(CACHED_SIZE, CACHED_SIZE, Bitmap.Config.ARGB_8888)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = thumbnail, full = null)
        adapter.onBindViewHolder(holder, 0)

        val replacement = createdViews.last()
        assertNotSame("a busy view must not receive a second setImage", staleView, replacement)
        assertNull("the busy view must be detached before its result lands", staleView.parent)
        assertEquals(CACHED_SIZE, replacement.sWidth)
        assertTrue("the cached bitmap is applied synchronously", replacement.isReady)

        val replacementEvents = RecordingListener()
        replacement.setOnImageEventListener(replacementEvents)

        drainUntilIdle()

        assertEquals("the detached view must be recycled, not kept half-initialised", 0, staleView.sWidth)
        assertEquals("the stale 1000x1000 source must never reach the new view", CACHED_SIZE, replacement.sWidth)
        assertEquals(CACHED_SIZE, replacement.sHeight)
        assertTrue(replacement.isReady)
        assertSame("the replacement stays attached", holder.itemView, replacement.parent)
        assertTrue("stale work must not report into the new view: ${replacementEvents.errors}", replacementEvents.errors.isEmpty())
        assertEquals("the stale decode must have reached its region decoder", 1, regionFactory.initUris.size)
    }

    @Test
    fun shouldKeepComparisonOverride_whenABusyUriDecodeDrains() {
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val staleView = createdViews.single()
        assertEquals(1, worker.pendingCount)

        val original = Bitmap.createBitmap(OVERRIDE_SIZE, OVERRIDE_SIZE, Bitmap.Config.ARGB_8888)
        adapter.showImageOverride(JPEG_PATH, original)
        ShadowLooper.shadowMainLooper().idle()
        adapter.onBindViewHolder(holder, 0)

        val photoView = photoViewOf(holder)
        assertSame("the comparison must show the original bitmap", original, photoView.bitmap())
        assertEquals(View.VISIBLE, photoView.visibility)
        assertNull(staleView.parent)

        drainUntilIdle()

        assertEquals("the detached view must be recycled after its callbacks drain", 0, staleView.sWidth)
        assertSame(
            "a stale tiled result must not replace the compared original",
            original,
            photoView.bitmap()
        )
        assertEquals(View.VISIBLE, photoView.visibility)
        assertEquals("no tiled view may reappear while comparing", 1, createdViews.size)
        assertEquals(View.GONE, loadingOverlayOf(holder).visibility)
        assertEquals("the stale URI decode must have run before the recycle", 1, regionFactory.initUris.size)

        val thumbnail = Bitmap.createBitmap(CACHED_SIZE, CACHED_SIZE, Bitmap.Config.ARGB_8888)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = thumbnail, full = null)
        adapter.clearImageOverride()
        ShadowLooper.shadowMainLooper().idle()
        adapter.onBindViewHolder(holder, 0)

        val restored = createdViews.last()
        assertNotSame(staleView, restored)
        assertEquals("ending the comparison must restore the tiled preview", CACHED_SIZE, restored.sWidth)
        assertTrue(restored.isReady)
        assertEquals("the restored preview must be the graded bitmap, not the URI", 1, regionFactory.initUris.size)
    }

    @Test
    fun shouldApplyTheImageAgain_whenTheSameTiledPathIsReboundAfterRecycle() {
        val thumbnail = Bitmap.createBitmap(CACHED_SIZE, CACHED_SIZE, Bitmap.Config.ARGB_8888)
        states[JPEG_PATH] = PhotoRenderState(thumbnail = thumbnail, full = null)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val first = createdViews.single()
        assertTrue(first.isReady)

        adapter.onViewRecycled(holder)

        assertEquals("an idle tiled view is recycled on recycle", 0, first.sWidth)
        assertNull(first.parent)

        adapter.onBindViewHolder(holder, 0)

        val rebound = createdViews.last()
        assertEquals(2, createdViews.size)
        assertNotSame(first, rebound)
        assertSame(holder.itemView, rebound.parent)
        assertEquals(CACHED_SIZE, rebound.sWidth)
        assertTrue("a same-path rebind must set its image again", rebound.isReady)
    }

    @Test
    fun shouldApplyTheBitmapAgain_whenTheSamePhotoViewPathIsReboundAfterRecycle() {
        val full = Bitmap.createBitmap(CACHED_SIZE, CACHED_SIZE, Bitmap.Config.ARGB_8888)
        states[RAW_PATH] = PhotoRenderState(thumbnail = null, full = full)
        adapter = createAdapter(RAW_PATH)

        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)
        val photoView = photoViewOf(holder)
        assertSame(full, photoView.bitmap())

        adapter.onViewRecycled(holder)

        assertNull("recycling the holder must drop the displayed bitmap", photoView.drawable)

        adapter.onBindViewHolder(holder, 0)

        assertSame(
            "a same-path rebind must re-apply the bitmap after the holder reset",
            full,
            photoView.bitmap()
        )
    }

    @Test
    fun shouldRejectAHolderBindThatRunsOffTheMainThread() {
        val holder = adapter.onCreateViewHolder(parent, 0)
        val thrown = arrayOfNulls<Throwable>(1)

        val thread = Thread {
            thrown[0] = runCatching { adapter.onBindViewHolder(holder, 0) }.exceptionOrNull()
        }
        thread.start()
        thread.join()

        assertTrue(
            "the holder must refuse to touch views off the main thread, got ${thrown[0]}",
            thrown[0] is IllegalStateException
        )
    }

    @Test
    fun shouldRejectRecyclingTheTiledHostOffTheMainThread() {
        val container = FrameLayout(RuntimeEnvironment.getApplication())
        val host = SubsamplingTiledHost(container)
        val view = host.createView(Executor { command -> command.run() })
        val thrown = arrayOfNulls<Throwable>(1)

        val thread = Thread {
            thrown[0] = runCatching { host.recycleView(view) }.exceptionOrNull()
        }
        thread.start()
        thread.join()

        assertTrue(
            "recycle() must never run on a worker thread, got ${thrown[0]}",
            thrown[0] is IllegalStateException
        )
    }

    private fun createAdapter(vararg paths: String): PhotoPagerAdapter {
        val mainHandler = Handler(Looper.getMainLooper())
        val created = PhotoPagerAdapter(
            renderStateProvider = { path -> states[path] ?: PhotoRenderState(thumbnail = null, full = null) },
            onBindRequest = { _, _ -> },
            jpegSourceInfoProvider = { null },
            isCurrentPageProvider = { position -> position == currentPage },
            isJpegProvider = { path -> path == JPEG_PATH },
            onPhotoScaleChanged = { _, _, _, _ -> },
            onPhotoTap = {},
            tiledDecodeExecutor = worker,
            postToMain = { runnable -> mainHandler.post(runnable) },
            configureTiledViewer = { view ->
                view.setRegionDecoderFactory(regionFactory)
                view.setBitmapDecoderFactory(bitmapFactory)
                view.setMinimumTileDpi(0)
                view.setMaxTileSize(MAX_TILE_SIZE)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
                createdViews += view
            }
        )
        created.updateItems(paths.mapIndexed { index, path -> PhotoEntity(id = index + 1L, path = path) })
        return created
    }

    /**
     * Runs are interleaved the way Android does it: worker tasks post their `AsyncTask` result to
     * the main looper, the looper runs it (which may derive more tile work), and the tracker's
     * sentinel is behind it in the same queue.
     */
    private fun drainUntilIdle() {
        repeat(MAX_DRAIN_ROUNDS) {
            var progressed = false
            while (worker.runNext()) {
                progressed = true
            }
            val mainLooper = ShadowLooper.shadowMainLooper()
            if (!mainLooper.isIdle) {
                mainLooper.idle()
                progressed = true
            }
            if (!progressed && worker.pendingCount == 0 && mainLooper.isIdle) {
                return
            }
        }
        fail("tiled decode queues did not settle (worker pending=${worker.pendingCount})")
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

    private class RecordingListener : SubsamplingScaleImageView.DefaultOnImageEventListener() {
        val errors = mutableListOf<Exception>()

        override fun onImageLoadError(e: Exception) {
            errors += e
        }
    }

    private class AdapterFakeRegionDecoderFactory(
        private val width: Int,
        private val height: Int
    ) : DecoderFactory<ImageRegionDecoder> {

        val initUris = mutableListOf<Uri>()

        override fun make(): ImageRegionDecoder {
            return object : ImageRegionDecoder {
                private var ready = false

                override fun init(context: Context, uri: Uri): Point {
                    initUris += uri
                    ready = true
                    return Point(width, height)
                }

                override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }

                override fun isReady(): Boolean = ready

                override fun recycle() {
                    ready = false
                }
            }
        }
    }

    private class AdapterFakeBitmapDecoderFactory(private val size: Int) : DecoderFactory<ImageDecoder> {
        override fun make(): ImageDecoder {
            return object : ImageDecoder {
                override fun decode(context: Context, source: Uri): Bitmap {
                    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                }
            }
        }
    }

    private class AdapterManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        val pendingCount: Int
            get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext(): Boolean {
            val task = tasks.poll() ?: return false
            task.run()
            return true
        }
    }

    private companion object {
        const val JPEG_PATH = "/photosync-test/pager/photo.jpg"
        const val RAW_PATH = "/photosync-test/pager/photo.dng"
        const val URI_WIDTH = 1000
        const val URI_HEIGHT = 1000
        const val CACHED_SIZE = 100
        const val OVERRIDE_SIZE = 60
        const val MAX_TILE_SIZE = 2048
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
        const val MAX_DRAIN_ROUNDS = 32
    }
}
