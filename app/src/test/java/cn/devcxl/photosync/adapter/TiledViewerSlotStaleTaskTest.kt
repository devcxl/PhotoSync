package cn.devcxl.photosync.adapter

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Validates the candidate fix slot ([TiledViewerSlot]) against the real
 * [SubsamplingScaleImageView] 3.10.0 and its real `AsyncTask` behavior.
 *
 * A bind that needs a new image must never mutate a view whose stale `TilesInitTask` is still in
 * flight. The slot detaches that view, gives the bind a fresh view with its own tracker, and
 * recycles the old view only after every tracked main-thread callback has run. The manual executor
 * makes the interleaving explicit instead of timing dependent.
 *
 * The pollution itself, without the slot, is pinned in
 * [SubsamplingScaleImageViewStaleTilesInitTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TiledViewerSlotStaleTaskTest {

    private lateinit var container: FrameLayout
    private lateinit var worker: SsivManualExecutor
    private lateinit var regionFactory: SsivFakeRegionDecoderFactory
    private lateinit var bitmapFactory: SsivFakeBitmapDecoderFactory
    private lateinit var host: SsivRecordingHost
    private lateinit var slot: TiledViewerSlot<SubsamplingScaleImageView>

    @Before
    fun setUp() {
        container = FrameLayout(RuntimeEnvironment.getApplication())
        worker = SsivManualExecutor()
        regionFactory = SsivFakeRegionDecoderFactory(STALE_WIDTH, STALE_HEIGHT)
        bitmapFactory = SsivFakeBitmapDecoderFactory(CACHED_SIZE)

        host = SsivRecordingHost(container) { view ->
            view.setMaxTileSize(MAX_TILE_SIZE)
            view.setMinimumTileDpi(0)
            view.setRegionDecoderFactory(regionFactory)
            view.setBitmapDecoderFactory(bitmapFactory)
        }
        val mainHandler = Handler(Looper.getMainLooper())
        slot = TiledViewerSlot(
            host = host,
            workerExecutor = worker,
            postToMain = { runnable -> mainHandler.post(runnable) }
        )
    }

    @After
    fun tearDown() {
        slot.release()
        drainUntil { host.created.all(host.recycled::contains) }
        container.removeAllViews()
    }

    @Test
    fun shouldGiveFreshViewAndRecycleStaleOne_whenBindNeedsNewImageDuringTilesInit() {
        val stale = slot.acquireForBind(needsNewImage = true)
        assertTrue(stale.isFresh)
        val staleListener = SsivRecordingListener()
        stale.view.setOnImageEventListener(staleListener)
        stale.view.setImage(ImageSource.uri(STALE_URI))
        assertEquals(1, worker.pendingCount)
        assertTrue(slot.hasInFlightTasks)

        val replacement = slot.acquireForBind(needsNewImage = true)

        assertTrue("busy view must be replaced, not mutated", replacement.isFresh)
        assertTrue(stale.view !== replacement.view)
        assertNull("retired view must be detached", stale.view.parent)
        assertSame(replacement.view, slot.currentView)
        assertTrue(host.detached.contains(stale.view))

        val liveListener = SsivRecordingListener()
        replacement.view.setOnImageEventListener(liveListener)
        val cached = Bitmap.createBitmap(CACHED_SIZE, CACHED_SIZE, Bitmap.Config.ARGB_8888)
        replacement.view.setImage(ImageSource.cachedBitmap(cached))

        drainUntil { host.recycled.contains(stale.view) }

        assertTrue("retired view must be recycled after its callbacks drain", host.recycled.contains(stale.view))
        assertEquals(CACHED_SIZE, replacement.view.getSWidth())
        assertTrue(
            "replacement view must not observe the stale task: ${liveListener.errors}",
            liveListener.errors.isEmpty()
        )
        assertTrue(replacement.view.isReady())
        assertTrue(
            "stale listener must be cleared by detach, so no late callback reaches it",
            staleListener.errors.isEmpty() && staleListener.readyCount == 0
        )
        assertEquals(STALE_URI, regionFactory.initUris.single())
        cached.recycle()
    }

    @Test
    fun shouldKeepRetiredViewAliveUntilTilesInitDrains_whenReleased() {
        val target = slot.acquireForBind(needsNewImage = true)
        val listener = SsivRecordingListener()
        target.view.setOnImageEventListener(listener)
        target.view.setImage(ImageSource.uri(STALE_URI))
        assertEquals(1, worker.pendingCount)

        slot.release()

        assertNull(slot.currentView)
        assertNull(target.view.parent)
        assertFalse("view with in-flight work must not be recycled immediately", host.recycled.contains(target.view))

        drainUntil { host.recycled.contains(target.view) }

        assertTrue(host.recycled.contains(target.view))
        assertEquals("recycle() must reset the source", 0, target.view.getSWidth())
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun shouldReuseView_whenNoDecodeTaskIsInFlight() {
        val first = slot.acquireForBind(needsNewImage = true)
        first.view.setImage(ImageSource.uri(STALE_URI))

        drainUntil { !slot.hasInFlightTasks && bitmapFactory.makeCount > 0 }

        val second = slot.acquireForBind(needsNewImage = true)

        assertFalse("idle view should be reused", second.isFresh)
        assertSame(first.view, second.view)
        assertSame(first.view, slot.currentView)
    }

    @Test
    fun shouldReuseView_whenBindDoesNotNeedNewImageEvenIfWorkIsInFlight() {
        val first = slot.acquireForBind(needsNewImage = true)
        first.view.setImage(ImageSource.uri(STALE_URI))
        assertEquals(1, worker.pendingCount)

        val same = slot.acquireForBind(needsNewImage = false)

        assertFalse(same.isFresh)
        assertSame(first.view, same.view)
        assertTrue(slot.hasInFlightTasks)
    }

    private fun drainUntil(condition: () -> Boolean) {
        repeat(MAX_DRAIN_ROUNDS) {
            if (condition()) return
            while (worker.runNext()) {
                // Keep running; the main looper may derive follow-up tasks.
            }
            ShadowLooper.shadowMainLooper().idle()
            if (condition()) return
        }
        fail("condition not met after $MAX_DRAIN_ROUNDS rounds")
    }

    private companion object {
        const val STALE_WIDTH = 1000
        const val STALE_HEIGHT = 1000
        const val CACHED_SIZE = 100
        const val MAX_TILE_SIZE = 2048
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
        const val MAX_DRAIN_ROUNDS = 32
        val STALE_URI: Uri = Uri.parse("photosync-test://stale/slot-photo")
    }
}

/** Host that builds real views with injected decoders and records lifecycle calls. */
private class SsivRecordingHost(
    private val container: FrameLayout,
    private val configure: (SubsamplingScaleImageView) -> Unit
) : TiledViewerSlot.Host<SubsamplingScaleImageView> {

    val created = mutableListOf<SubsamplingScaleImageView>()
    val detached = mutableListOf<SubsamplingScaleImageView>()
    val recycled = mutableListOf<SubsamplingScaleImageView>()

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
        detached += view
    }

    override fun recycleView(view: SubsamplingScaleImageView) {
        recycled += view
        view.recycle()
    }

    private companion object {
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
    }
}
