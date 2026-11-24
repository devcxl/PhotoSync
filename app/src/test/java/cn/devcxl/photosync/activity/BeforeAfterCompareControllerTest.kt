package cn.devcxl.photosync.activity

import android.app.Application
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View

import kotlin.coroutines.CoroutineContext

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.adapter.PhotoRenderState
import cn.devcxl.photosync.databinding.ActivityMainBinding

/**
 * Regression tests for the asynchronous decode of [BeforeAfterCompareController].
 *
 * A decode takes long enough that the session that asked for it can be gone by the time it lands:
 * the user releases the hold, swipes to another photo, or holds the *same* photo again. The
 * controller must then drop the result instead of painting a stale original, ending the live
 * comparison, or — the bug these tests pin down — calling `clearImageOverride` and wiping the
 * override that the newer session is showing.
 *
 * The whole flow runs on two manually drained dispatchers, so each decode completes exactly when a
 * test says so. No thread, no wall clock, no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BeforeAfterCompareControllerTest {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: PhotoPagerAdapter
    private lateinit var cache: OriginalPreviewCache
    private lateinit var controller: BeforeAfterCompareController
    private lateinit var scope: CoroutineScope

    /** Stands in for the main thread: the controller's scope dispatcher. */
    private val mainDispatcher = ManualDispatcher()

    /** Stands in for `Dispatchers.IO`: where `decodeOriginal` runs. */
    private val decodeDispatcher = ManualDispatcher()

    /** Paths the controller asked to decode, in order. */
    private val decodedPaths = mutableListOf<String>()

    /** Results handed out by `decodeOriginal`, in order; an empty queue decodes to `null`. */
    private val scriptedOriginals = ArrayDeque<Bitmap?>()

    @Before
    fun setUp() {
        val themed = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_PhotoSync)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(themed))
        adapter = PhotoPagerAdapter(
            renderStateProvider = { PhotoRenderState(thumbnail = null, full = null) },
            onBindRequest = { _, _ -> },
            jpegSourceInfoProvider = { null },
            isCurrentPageProvider = { false },
            isJpegProvider = { false },
            onPhotoScaleChanged = { _, _, _, _ -> },
            onPhotoTap = {}
        )
        cache = OriginalPreviewCache()
        scope = CoroutineScope(Job() + mainDispatcher)
        controller = BeforeAfterCompareController(
            binding = binding,
            adapter = adapter,
            scope = scope,
            cache = cache,
            decodeOriginal = { path ->
                decodedPaths += path
                scriptedOriginals.removeFirstOrNull()
            },
            decodeDispatcher = decodeDispatcher
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun shouldKeepTheLiveOriginal_whenASupersededDecodeSucceedsForAnotherPath() {
        val abandonedOriginal = bitmap()
        val liveOriginal = bitmap()
        scriptedOriginals.addLast(abandonedOriginal)
        cache.put(PATH_B, liveOriginal)

        controller.setComparing(PATH_A, true)
        runMain()
        // The user releases A and holds B before A's decode lands; B is already cached, so its
        // original is on screen immediately.
        controller.setComparing(PATH_A, false)
        controller.setComparing(PATH_B, true)
        assertSame(liveOriginal, adapter.overrideFor(PATH_B))
        assertEquals(View.VISIBLE, binding.compareStatusPill.visibility)

        drain()

        assertSame(
            "the superseded decode must not clear the live comparison",
            liveOriginal,
            adapter.overrideFor(PATH_B)
        )
        assertEquals(View.VISIBLE, binding.compareStatusPill.visibility)
    }

    @Test
    fun shouldKeepComparing_whenADecodeOfAnEndedSessionFailsForTheLivePath() {
        scriptedOriginals.addLast(null)
        val liveOriginal = bitmap()
        scriptedOriginals.addLast(liveOriginal)

        controller.setComparing(PATH_A, true)
        runMain()
        // Release and hold the same photo again while the first decode is still out.
        controller.clear()
        controller.setComparing(PATH_A, true)
        runMain()

        decodeDispatcher.runNext()
        runMain()

        assertEquals(
            "a superseded failure must not end the live comparison",
            View.VISIBLE,
            binding.compareStatusPill.visibility
        )
        assertNull("the live decode has not landed yet", adapter.overrideFor(PATH_A))

        drain()

        assertSame(liveOriginal, adapter.overrideFor(PATH_A))
        assertEquals(View.VISIBLE, binding.compareStatusPill.visibility)
    }

    @Test
    fun shouldShowOnlyTheNewestOriginal_whenTheSamePathIsComparedAgain() {
        val staleOriginal = bitmap()
        val freshOriginal = bitmap()
        scriptedOriginals.addLast(staleOriginal)
        scriptedOriginals.addLast(freshOriginal)

        controller.setComparing(PATH_A, true)
        runMain()
        controller.clear()
        controller.setComparing(PATH_A, true)
        runMain()

        // The abandoned decode of the same path lands first: it must publish nothing even though
        // its path matches the live session.
        decodeDispatcher.runNext()
        runMain()

        assertNull("a superseded decode must not publish for the same path", adapter.overrideFor(PATH_A))
        assertEquals(View.VISIBLE, binding.compareStatusPill.visibility)

        drain()

        assertSame(freshOriginal, adapter.overrideFor(PATH_A))
        assertEquals(View.VISIBLE, binding.compareStatusPill.visibility)
    }

    @Test
    fun shouldNotShowAnything_whenTheDecodeLandsAfterTheUserReleased() {
        scriptedOriginals.addLast(bitmap())

        controller.setComparing(PATH_A, true)
        runMain()
        controller.clear()
        drain()

        assertNull(adapter.overrideFor(PATH_A))
        assertEquals(View.GONE, binding.compareStatusPill.visibility)
    }

    @Test
    fun shouldEndTheComparison_whenTheDecodeOfTheLiveSessionFails() {
        scriptedOriginals.addLast(null)

        controller.setComparing(PATH_A, true)
        drain()

        assertNull(adapter.overrideFor(PATH_A))
        assertEquals(View.GONE, binding.compareStatusPill.visibility)
    }

    @Test
    fun shouldDecodeOnce_whenTheSamePathIsHeldTwice() {
        val original = bitmap()
        scriptedOriginals.addLast(original)

        controller.setComparing(PATH_A, true)
        controller.setComparing(PATH_A, true)
        drain()

        assertEquals(listOf(PATH_A), decodedPaths)
        assertSame(original, adapter.overrideFor(PATH_A))
        assertEquals(View.VISIBLE, binding.compareStatusPill.visibility)
    }

    @Test
    fun shouldKeepComparing_whenAnEndArrivesForAnotherPath() {
        val original = bitmap()
        cache.put(PATH_A, original)

        controller.setComparing(PATH_A, true)
        controller.setComparing(PATH_B, false)

        assertSame(original, adapter.overrideFor(PATH_A))
        assertEquals(View.VISIBLE, binding.compareStatusPill.visibility)

        controller.setComparing(PATH_A, false)

        assertNull(adapter.overrideFor(PATH_A))
        assertEquals(View.GONE, binding.compareStatusPill.visibility)
    }

    /** Runs every continuation the main dispatcher holds. */
    private fun runMain() = mainDispatcher.runAll()

    /** Runs continuations on both dispatchers until the decode hand-off has settled. */
    private fun drain() {
        while (!mainDispatcher.isEmpty || !decodeDispatcher.isEmpty) {
            mainDispatcher.runAll()
            decodeDispatcher.runAll()
        }
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888)

    /**
     * Queues dispatched continuations instead of running them, so a test decides the exact order in
     * which the decode and its callback run.
     */
    private class ManualDispatcher : CoroutineDispatcher() {

        private val queued = ArrayDeque<Runnable>()

        val isEmpty: Boolean get() = queued.isEmpty()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        /** Runs the currently queued continuations; ones queued meanwhile run in the same drain. */
        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }

        /** Runs exactly one queued continuation, leaving the rest pending. */
        fun runNext() = queued.removeFirst().run()
    }

    private companion object {
        const val PATH_A = "/photosync-test/compare/a.jpg"
        const val PATH_B = "/photosync-test/compare/b.jpg"
        const val BITMAP_SIZE = 64
    }
}
