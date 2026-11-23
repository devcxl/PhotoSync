package cn.devcxl.photosync.adapter

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Pins the SubsamplingScaleImageView 3.10.0 stale `TilesInitTask` failure that `PhotoPagerAdapter`
 * used to expose when a tiled JPEG page became a cached-bitmap page, or when the tiled view was
 * recycled while a tiles-init task was still in flight.
 *
 * The library never cancels its `AsyncTask`s. The stale task captures the old `Uri`, but
 * `onTilesInited` assigns the stale decoder and source dimensions back to the view. The next draw
 * calls `initialiseBaseLayer`, which reads the view's `uri` field - already reset to `null` by
 * `setImage(cachedBitmap)` or `recycle()`. The resulting `BitmapLoadTask` throws an NPE from
 * `source.toString()`, delivered through the public `onImageLoadError` callback.
 *
 * These tests drive the real library with a manual executor and fake decoder factories, so no
 * device, media file or real decode is involved. The fix is validated separately through
 * `TiledViewerSlot` in [TiledViewerSlotStaleTaskTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubsamplingScaleImageViewStaleTilesInitTest {

    private lateinit var worker: SsivManualExecutor
    private lateinit var regionFactory: SsivFakeRegionDecoderFactory
    private lateinit var bitmapFactory: SsivFakeBitmapDecoderFactory
    private lateinit var listener: SsivRecordingListener
    private lateinit var view: SubsamplingScaleImageView
    private lateinit var canvasBitmap: Bitmap

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        worker = SsivManualExecutor()
        regionFactory = SsivFakeRegionDecoderFactory(STALE_WIDTH, STALE_HEIGHT)
        bitmapFactory = SsivFakeBitmapDecoderFactory(CACHED_SIZE)
        listener = SsivRecordingListener()

        view = SubsamplingScaleImageView(context).apply {
            setMaxTileSize(MAX_TILE_SIZE)
            setMinimumTileDpi(0)
            setExecutor(worker)
            setRegionDecoderFactory(regionFactory)
            setBitmapDecoderFactory(bitmapFactory)
            setOnImageEventListener(listener)
            measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
        }
        canvasBitmap = Bitmap.createBitmap(VIEW_WIDTH, VIEW_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    @After
    fun tearDown() {
        view.recycle()
        canvasBitmap.recycle()
    }

    @Test
    fun shouldExposeNullUriFailure_whenCachedBitmapReplacesViewBeforeStaleTilesInit() {
        val cached = Bitmap.createBitmap(CACHED_SIZE, CACHED_SIZE, Bitmap.Config.ARGB_8888)

        view.setImage(ImageSource.uri(STALE_URI))
        assertEquals("setImage(uri) must queue exactly one TilesInitTask", 1, worker.pendingCount)

        view.setImage(ImageSource.cachedBitmap(cached))
        assertEquals("cached bitmap must not queue async work", 1, worker.pendingCount)
        assertEquals(CACHED_SIZE, view.getSWidth())
        assertTrue(listener.errors.isEmpty())

        runNextTask()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("stale task overwrote the source width", STALE_WIDTH, view.getSWidth())
        assertEquals(STALE_URI, regionFactory.only().initUri)
        assertTrue("the stale callback alone must not report an error yet", listener.errors.isEmpty())

        view.draw(SsivFixedMaxCanvas(canvasBitmap, MAX_TILE_SIZE, MAX_TILE_SIZE))

        assertEquals("next draw schedules BitmapLoadTask with the null uri", 1, worker.pendingCount)
        assertTrue(listener.errors.isEmpty())

        runNextTask()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals(1, listener.errors.size)
        val error = listener.errors.single()
        assertTrue("expected NPE, got $error", error is NullPointerException)
        assertTrue(
            "NPE must originate in BitmapLoadTask.doInBackground:\n${error.stackTraceToString()}",
            error.stackTraceToString().contains("SubsamplingScaleImageView\$BitmapLoadTask.doInBackground")
        )
        assertEquals(
            "the null-uri failure happens before the bitmap decoder factory is used",
            0,
            bitmapFactory.makeCount
        )
    }

    @Test
    fun shouldResurrectSourceAndExposeNullUriFailure_whenRecycleRunsBeforeStaleTilesInit() {
        view.setImage(ImageSource.uri(STALE_URI))
        assertEquals(1, worker.pendingCount)

        view.recycle()
        assertEquals(0, view.getSWidth())
        assertFalse(view.hasImage())

        runNextTask()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("stale task resurrected the recycled view's source width", STALE_WIDTH, view.getSWidth())
        assertFalse(view.hasImage())
        assertTrue("stale callback derived a base-layer load after recycle", worker.pendingCount >= 1)
        assertTrue(regionFactory.only().recycled)

        runNextTask()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals(1, listener.errors.size)
        val error = listener.errors.single()
        assertTrue("expected NPE, got $error", error is NullPointerException)
        assertTrue(
            "NPE must originate in BitmapLoadTask.doInBackground:\n${error.stackTraceToString()}",
            error.stackTraceToString().contains("SubsamplingScaleImageView\$BitmapLoadTask.doInBackground")
        )
        assertEquals(0, bitmapFactory.makeCount)
    }

    @Test
    fun shouldLoadNormally_whenNoReplacementHappens() {
        view.setImage(ImageSource.uri(STALE_URI))

        drainUntilQuiescent()

        assertTrue("control run must not report errors: ${listener.errors}", listener.errors.isEmpty())
        assertEquals(1, bitmapFactory.makeCount)
        assertEquals(STALE_URI, bitmapFactory.lastDecodeUri)
        assertEquals(1, listener.readyCount)
        assertEquals(1, listener.imageLoadedCount)
    }

    /** Runs queued worker tasks and drains main until no work is left. */
    private fun drainUntilQuiescent() {
        repeat(MAX_DRAIN_ROUNDS) {
            while (worker.runNext()) {
                // Keep draining; the main looper may derive more tasks below.
            }
            ShadowLooper.shadowMainLooper().idle()
            if (worker.pendingCount == 0) return
        }
        fail("worker did not become quiescent after $MAX_DRAIN_ROUNDS rounds")
    }

    private fun runNextTask() {
        assertTrue("expected a queued task", worker.runNext())
    }

    private companion object {
        const val STALE_WIDTH = 1000
        const val STALE_HEIGHT = 1000
        const val CACHED_SIZE = 100
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
        const val MAX_TILE_SIZE = 2048
        const val MAX_DRAIN_ROUNDS = 32
        val STALE_URI: Uri = Uri.parse("photosync-test://stale/photo-1")
    }
}
