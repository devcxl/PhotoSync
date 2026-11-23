package cn.devcxl.photosync.adapter

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Real-thread Robolectric regression for the SSIV stale `TilesInitTask` family.
 *
 * Unlike the manual-executor tests, this class lets SSIV's `AsyncTask` bodies run on a real
 * single-thread executor. `CountDownLatch` gates inside the fake decoders hold a body right before
 * it posts its result, so the test can inspect the in-flight state before the main looper is
 * pumped:
 *
 * - the SSIV `onPost` result is posted by the worker before the tracker's sentinel (FIFO on main);
 * - the derived `BitmapLoadTask` from `onTilesInited` is counted while the first sentinel is still
 *   queued, so the tracker cannot drain early;
 * - `retire`, listener clear and `recycle` all run on the main looper.
 *
 * The whole class runs for API 24 and API 35 (both supported by Robolectric 4.16.1); no device is
 * involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = Application::class)
class SsivRealThreadRegressionTest {

    private lateinit var worker: ExecutorService
    private lateinit var mainHandler: Handler
    private lateinit var mainLooper: Looper
    private lateinit var mainThread: Thread
    private lateinit var regionFactory: GatedRegionDecoderFactory
    private lateinit var bitmapFactory: GatedBitmapDecoderFactory
    private lateinit var decodedBitmap: Bitmap
    private lateinit var view: SubsamplingScaleImageView
    private lateinit var tracker: SsivTaskTracker
    private lateinit var listener: ThreadRecordingListener
    private lateinit var events: MutableList<String>
    private lateinit var probes: MutableList<Int>
    private lateinit var drainThreads: MutableList<Thread>
    private lateinit var drainLoopers: MutableList<Looper?>
    private lateinit var releaseInit: CountDownLatch
    private lateinit var releaseDecode: CountDownLatch
    private lateinit var firstSentinel: CountDownLatch
    private lateinit var secondSentinel: CountDownLatch
    private val slotHolder = arrayOfNulls<TiledViewerSlot<SubsamplingScaleImageView>>(1)

    @Before
    fun setUp() {
        mainLooper = Looper.getMainLooper()
        mainThread = Thread.currentThread()
        assertEquals("Robolectric tests must start on the main looper", mainLooper, Looper.myLooper())
        mainHandler = Handler(mainLooper)
        worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, WORKER_NAME) }

        releaseInit = CountDownLatch(1)
        releaseDecode = CountDownLatch(1)
        regionFactory = GatedRegionDecoderFactory(STALE_WIDTH, STALE_HEIGHT, releaseInit)
        decodedBitmap = Bitmap.createBitmap(CACHED_SIZE, CACHED_SIZE, Bitmap.Config.ARGB_8888)
        bitmapFactory = GatedBitmapDecoderFactory(decodedBitmap, releaseDecode)

        events = CopyOnWriteArrayList()
        probes = CopyOnWriteArrayList()
        drainThreads = CopyOnWriteArrayList()
        drainLoopers = CopyOnWriteArrayList()
        listener = ThreadRecordingListener(events)

        firstSentinel = CountDownLatch(1)
        secondSentinel = CountDownLatch(1)
        val sentinelsPosted = AtomicInteger()
        tracker = SsivTaskTracker(
            backgroundExecutor = worker,
            postToMain = { sentinel ->
                // Runs on the worker after the SSIV AsyncTask has posted its own result.
                mainHandler.post { probes += tracker.outstandingTaskCount }
                val posted = mainHandler.post(sentinel)
                if (posted) {
                    when (sentinelsPosted.incrementAndGet()) {
                        1 -> firstSentinel.countDown()
                        2 -> secondSentinel.countDown()
                    }
                }
                posted
            },
            onDrained = {
                events += "drain"
                drainThreads += Thread.currentThread()
                drainLoopers += Looper.myLooper()
            }
        )

        val context = RuntimeEnvironment.getApplication()
        view = SubsamplingScaleImageView(context).apply {
            setMaxTileSize(MAX_TILE_SIZE)
            setMinimumTileDpi(0)
            setExecutor(tracker)
            setRegionDecoderFactory(regionFactory)
            setBitmapDecoderFactory(bitmapFactory)
            setOnImageEventListener(listener)
            measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
        }
    }

    @After
    fun tearDown() {
        slotHolder[0]?.release()
        releaseInit.countDown()
        releaseDecode.countDown()
        worker.shutdownNow()
        worker.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)
        view.recycle()
        decodedBitmap.recycle()
    }

    @Test
    fun shouldDeliverSsivOnPostBeforeDrain_andCountDerivedTask() {
        view.setImage(ImageSource.uri(STALE_URI))

        assertTrue("worker must enter region init", regionFactory.initEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue("SSIV body must run off the main thread", regionFactory.initThread !== mainThread)
        assertEquals(1, tracker.outstandingTaskCount)
        ShadowLooper.shadowMainLooper().idle()
        assertTrue("no main-thread result before the gate is released", events.isEmpty())
        assertEquals(0, bitmapFactory.makeCount.get())

        tracker.retire()
        assertTrue(tracker.isRetired)
        assertFalse(tracker.isDrained)

        releaseInit.countDown()
        assertTrue("first sentinel must be posted", firstSentinel.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("derived task must be counted before the first drain", listOf(2), probes.toList())
        assertEquals(1, tracker.outstandingTaskCount)
        assertFalse("must not drain while the derived task is outstanding", tracker.isDrained)

        assertTrue("derived BitmapLoadTask must enter decode", bitmapFactory.decodeEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue("bitmap decode must run off the main thread", bitmapFactory.decodeThread !== mainThread)
        releaseDecode.countDown()

        assertTrue("second sentinel must be posted", secondSentinel.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        ShadowLooper.shadowMainLooper().idle()

        assertTrue(tracker.isDrained)
        assertEquals(listOf(2, 1), probes.toList())
        assertEquals("drain", events.last())
        assertTrue(
            "SSIV callback must be delivered before the drain: $events",
            events.dropLast(1).isNotEmpty() && events.dropLast(1).all { it.startsWith("on") }
        )
        assertTrue("listener callbacks must run on main", listener.callbackThreads.all { it === mainThread })
        assertEquals(1, drainThreads.size)
        assertSame("drain must run on the main thread", mainThread, drainThreads.single())
        assertEquals(mainLooper, drainLoopers.single())
        assertTrue("no image load errors: ${listener.errors}", listener.errors.isEmpty())
        assertEquals(CACHED_SIZE, view.getSWidth())
    }

    @Test
    fun shouldRetireClearAndRecycleOnMainThread_whenBusyViewIsReplaced() {
        val sentinelsPosted = AtomicInteger()
        val slotFirstSentinel = CountDownLatch(1)
        val slotSecondSentinel = CountDownLatch(1)
        val container = FrameLayout(RuntimeEnvironment.getApplication())
        val host = MainThreadRecordingHost(container) { createdView ->
            createdView.setMaxTileSize(MAX_TILE_SIZE)
            createdView.setMinimumTileDpi(0)
            createdView.setRegionDecoderFactory(regionFactory)
            createdView.setBitmapDecoderFactory(bitmapFactory)
        }
        val slot = TiledViewerSlot(
            host = host,
            workerExecutor = worker,
            postToMain = { sentinel ->
                val posted = mainHandler.post(sentinel)
                if (posted) {
                    when (sentinelsPosted.incrementAndGet()) {
                        1 -> slotFirstSentinel.countDown()
                        2 -> slotSecondSentinel.countDown()
                    }
                }
                posted
            }
        )
        slotHolder[0] = slot
        assertEquals("slot lifecycle calls must be made on main", mainLooper, Looper.myLooper())

        val stale = slot.acquireForBind(needsNewImage = true)
        assertTrue(stale.isFresh)
        val staleListener = SsivRecordingListener()
        stale.view.setOnImageEventListener(staleListener)
        stale.view.setImage(ImageSource.uri(STALE_URI))
        assertTrue("worker must enter region init", regionFactory.initEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS))

        val replacement = slot.acquireForBind(needsNewImage = true)

        assertTrue("busy view must be replaced", replacement.isFresh)
        assertTrue(stale.view !== replacement.view)
        assertNull("retired view must be detached", stale.view.parent)
        assertEquals("detach/clear must run on main", listOf(mainLooper), host.detachLoopers)
        assertFalse("must not recycle before callbacks drain", host.recycledViews.contains(stale.view))

        releaseInit.countDown()
        assertTrue("first sentinel must be posted", slotFirstSentinel.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        ShadowLooper.shadowMainLooper().idle()
        assertTrue("derived decode must enter", bitmapFactory.decodeEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertFalse(host.recycledViews.contains(stale.view))

        releaseDecode.countDown()
        assertTrue("second sentinel must be posted", slotSecondSentinel.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("retired view must be recycled on main", listOf(mainLooper), host.recycleLoopers)
        assertTrue(host.recycledViews.contains(stale.view))
        assertSame(replacement.view, slot.currentView)
        assertTrue("cleared listener must not receive stale callbacks", staleListener.errors.isEmpty())
        assertEquals(0, staleListener.readyCount)
        assertEquals("replacement view must stay clean", 0, replacement.view.getSWidth())
    }

    private companion object {
        const val STALE_WIDTH = 1000
        const val STALE_HEIGHT = 1000
        const val CACHED_SIZE = 100
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
        const val MAX_TILE_SIZE = 2048
        const val AWAIT_SECONDS = 10L
        const val WORKER_NAME = "ssiv-real-worker"
        val STALE_URI: Uri = Uri.parse("photosync-test://stale/real-thread")
    }
}

private class ThreadRecordingListener(
    private val events: MutableList<String>
) : SubsamplingScaleImageView.DefaultOnImageEventListener() {

    val errors = CopyOnWriteArrayList<Exception>()
    val callbackThreads = CopyOnWriteArrayList<Thread>()

    override fun onReady() = record("onReady")

    override fun onImageLoaded() = record("onImageLoaded")

    override fun onImageLoadError(e: Exception) {
        errors += e
        record("onImageLoadError")
    }

    private fun record(name: String) {
        events += name
        callbackThreads += Thread.currentThread()
    }
}

private class GatedRegionDecoderFactory(
    private val width: Int,
    private val height: Int,
    private val releaseInit: CountDownLatch
) : DecoderFactory<ImageRegionDecoder> {

    val initEntered = CountDownLatch(1)

    @Volatile
    var initThread: Thread? = null

    @Volatile
    var initUri: Uri? = null

    override fun make(): ImageRegionDecoder = object : ImageRegionDecoder {
        private var ready = false

        override fun init(context: Context, uri: Uri): Point {
            initThread = Thread.currentThread()
            initUri = uri
            initEntered.countDown()
            check(releaseInit.await(AWAIT_SECONDS, TimeUnit.SECONDS)) { "region init gate timed out" }
            ready = true
            return Point(width, height)
        }

        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
            throw UnsupportedOperationException("tile decode is not expected in this test")
        }

        override fun isReady(): Boolean = ready

        override fun recycle() {
            ready = false
        }
    }

    private companion object {
        const val AWAIT_SECONDS = 10L
    }
}

private class GatedBitmapDecoderFactory(
    private val bitmap: Bitmap,
    private val releaseDecode: CountDownLatch
) : DecoderFactory<ImageDecoder> {

    val decodeEntered = CountDownLatch(1)
    val makeCount = AtomicInteger()

    @Volatile
    var decodeThread: Thread? = null

    @Volatile
    var lastDecodeUri: Uri? = null

    override fun make(): ImageDecoder {
        makeCount.incrementAndGet()
        return object : ImageDecoder {
            override fun decode(context: Context, source: Uri): Bitmap {
                decodeThread = Thread.currentThread()
                lastDecodeUri = source
                decodeEntered.countDown()
                check(releaseDecode.await(AWAIT_SECONDS, TimeUnit.SECONDS)) { "bitmap decode gate timed out" }
                return bitmap
            }
        }
    }

    private companion object {
        const val AWAIT_SECONDS = 10L
    }
}

private class MainThreadRecordingHost(
    private val container: FrameLayout,
    private val configure: (SubsamplingScaleImageView) -> Unit
) : TiledViewerSlot.Host<SubsamplingScaleImageView> {

    val created = CopyOnWriteArrayList<SubsamplingScaleImageView>()
    val detached = CopyOnWriteArrayList<SubsamplingScaleImageView>()
    val recycledViews = CopyOnWriteArrayList<SubsamplingScaleImageView>()
    val detachLoopers = CopyOnWriteArrayList<Looper?>()
    val recycleLoopers = CopyOnWriteArrayList<Looper?>()

    override fun createView(executor: Executor): SubsamplingScaleImageView {
        val view = SubsamplingScaleImageView(container.context)
        view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        view.visibility = View.GONE
        configure(view)
        view.setExecutor(executor)
        created += view
        return view
    }

    override fun attachView(view: SubsamplingScaleImageView) {
        container.addView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
    }

    override fun detachView(view: SubsamplingScaleImageView) {
        container.removeView(view)
        view.setOnImageEventListener(null)
        view.setOnStateChangedListener(null)
        view.setOnClickListener(null)
        detachLoopers += Looper.myLooper()
        detached += view
    }

    override fun recycleView(view: SubsamplingScaleImageView) {
        recycleLoopers += Looper.myLooper()
        recycledViews += view
        view.recycle()
    }

    private companion object {
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
    }
}
