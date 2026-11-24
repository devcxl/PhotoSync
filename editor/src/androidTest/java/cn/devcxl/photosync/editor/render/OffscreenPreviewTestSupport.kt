package cn.devcxl.photosync.editor.render

import java.io.File
import java.util.UUID

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Fixtures and pixel assertions shared by the offscreen GPU preview instrumented tests.
 *
 * The tests deliberately touch only the production seams that must survive the current refactor:
 * [OffscreenPreviewRenderer.renderNow] for synchronous renders, [OffscreenPreviewRenderer.render]
 * for the worker path, [EditorSource]/[EditorSourceLease] as the decoded input and
 * [PreviewCacheWriter.writeBitmapPreviewTo] as the CPU fallback reference. Everything else here is
 * plain `Bitmap`/JPEG handling, so a change to the internal pass orchestration does not force edits
 * in the tests.
 *
 * ## Device requirement and skips
 * GPU pixel tests require a headless EGL context with OpenGL ES 3.x, probed independently by
 * [HeadlessGlProbe]. Only a device below that requirement skips. Once the probe passes every graph
 * must render: a `null` from the renderer fails the test instead of being treated as unsupported
 * hardware.
 */
internal object OffscreenPreviewTestSupport {

    const val SOURCE_WIDTH = 480
    const val SOURCE_HEIGHT = 320

    /** Longest edge shared by the GPU chain and [PreviewCacheWriter]. */
    val PREVIEW_MAX_EDGE_PX: Int get() = OffscreenPreviewFrames.MAX_EDGE_PX

    /** Per-channel budget for the flat quadrant markers after JPEG + resampling. */
    const val MARKER_TOLERANCE = 12

    val RED: Int = Color.rgb(214, 40, 40)
    val GREEN: Int = Color.rgb(46, 160, 79)
    val BLUE: Int = Color.rgb(29, 95, 209)
    val YELLOW: Int = Color.rgb(229, 197, 24)

    /** Normalized sample points of the four source quadrants. */
    enum class Quadrant(val u: Float, val v: Float) {
        TOP_LEFT(0.25f, 0.25f),
        TOP_RIGHT(0.75f, 0.25f),
        BOTTOM_LEFT(0.25f, 0.75f),
        BOTTOM_RIGHT(0.75f, 0.75f)
    }

    /** One quadrant colour per sampled point, in the stored (not yet rotated) image. */
    val QUADRANT_COLORS: Map<Quadrant, Int> = linkedMapOf(
        Quadrant.TOP_LEFT to RED,
        Quadrant.TOP_RIGHT to GREEN,
        Quadrant.BOTTOM_LEFT to BLUE,
        Quadrant.BOTTOM_RIGHT to YELLOW
    )

    /**
     * Quadrant colours after one clockwise quarter turn: the stored bottom-left corner becomes the
     * displayed top-left corner, the stored top-left becomes top-right, and so on.
     */
    val CLOCKWISE_QUARTER_TURN_COLORS: Map<Quadrant, Int> = linkedMapOf(
        Quadrant.TOP_LEFT to BLUE,
        Quadrant.TOP_RIGHT to RED,
        Quadrant.BOTTOM_LEFT to YELLOW,
        Quadrant.BOTTOM_RIGHT to GREEN
    )

    /** Creates an empty, uniquely named cache workspace that the caller deletes afterwards. */
    fun newWorkspace(label: String): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "offscreen-preview-$label-${UUID.randomUUID()}")
        check(directory.mkdirs() || directory.isDirectory) {
            "Could not create the test workspace $directory"
        }
        return directory
    }

    /**
     * Writes a JPEG whose four quadrants carry [QUADRANT_COLORS] and stamps [orientation] into its
     * EXIF, exactly like a camera would for a rotated shot.
     */
    fun createQuadrantJpeg(
        directory: File,
        name: String,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): File {
        if (!directory.isDirectory) {
            check(directory.mkdirs()) { "Could not create $directory" }
        }
        val bitmap = Bitmap.createBitmap(SOURCE_WIDTH, SOURCE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { style = Paint.Style.FILL }
            val halfWidth = SOURCE_WIDTH / 2
            val halfHeight = SOURCE_HEIGHT / 2
            for ((quadrant, color) in QUADRANT_COLORS) {
                paint.color = color
                val left = if (quadrant.u < 0.5f) 0 else halfWidth
                val top = if (quadrant.v < 0.5f) 0 else halfHeight
                canvas.drawRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + halfWidth).toFloat(),
                    (top + halfHeight).toFloat(),
                    paint
                )
            }
            val file = File(directory, name)
            file.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream)) {
                    "Could not write $file"
                }
            }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            return file
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes [path] through the production editor source path, including EXIF uprighting. */
    fun loadSource(path: String): EditorSource = checkNotNull(
        EditorSourceLoader.load(sourcePath = path, isRaw = false, preferLinear = false)
    ) { "The editor source could not be decoded: $path" }

    /** The decoded bitmap behind a JPEG [source]; also the CPU writer's input. */
    fun sourceBitmap(source: EditorSource): Bitmap {
        val bitmapSource = source as? EditorSource.BitmapSource
            ?: error("Expected a BitmapSource for a JPEG, got ${source::class.java.simpleName}")
        return bitmapSource.full
    }

    /** Builds the request shared by the synchronous and the worker render entry points. */
    fun previewRequest(source: EditorSource, graph: EditGraph, outDir: File): OffscreenPreviewRequest =
        OffscreenPreviewRequest(
            source = source,
            graph = graph,
            outDir = outDir
        )

    /**
     * Raw synchronous renderer call; `null` means the caller must fall back to the CPU writer.
     *
     * Pixel tests must use [renderOnGpuRequired] instead: this nullable form exists only for the
     * fallback tests that intentionally induce a failure, and using it elsewhere would let a GPU
     * regression pass as a skipped render.
     */
    fun renderOnGpuNullable(source: EditorSource, graph: EditGraph, outDir: File): File? =
        OffscreenPreviewRenderer.renderNow(previewRequest(source, graph, outDir))

    /** Acquires a shared source lease for the asynchronous worker path. */
    suspend fun acquireLease(path: String): EditorSourceLease = checkNotNull(
        RecentEditorSourceCache.shared.acquire(
            sourcePath = path,
            isRaw = false,
            preferLinear = false
        )
    ) { "The recent editor source cache could not decode $path" }

    /** Renders through the serial worker; ownership of [lease] transfers to the renderer. */
    suspend fun renderOnWorker(request: OffscreenPreviewRequest, lease: EditorSourceLease): File? =
        OffscreenPreviewRenderer.render(request, lease)

    /**
     * Asserts the suite's device requirement and returns the independently probed capabilities.
     *
     * This is the only skip path of the GPU tests. The probe is raw EGL/GLES and never compiles the
     * production shaders, so a broken shader, FBO or read-back cannot masquerade as unsupported
     * hardware.
     */
    fun requireHeadlessGpu(): HeadlessGlProbe.Capabilities {
        val capabilities = HeadlessGlProbe.detect()
        Assume.assumeTrue(
            "This suite intentionally requires a higher device class than the app: a headless EGL " +
                "context on OpenGL ES 3.x with at least ${OffscreenPreviewFrames.MAX_EDGE_PX}px " +
                "textures. This device does not meet that requirement, so GPU pixel/worker " +
                "coverage is skipped here; the CPU fallback remains covered by " +
                "OffscreenPreviewRendererFallbackTest.",
            capabilities != null
        )
        return checkNotNull(capabilities)
    }

    /**
     * Asserts a worker render produced a file on a device that already passed [requireHeadlessGpu].
     *
     * Used inside coroutines, where a `null` from the worker must be reported as a regression rather
     * than swallowed or turned into a skip.
     */
    fun requireRendered(
        label: String,
        capabilities: HeadlessGlProbe.Capabilities,
        file: File?
    ): File {
        assertNotNull(
            "$label: the device provides ${capabilities.summary}, so the queued worker render must " +
                "publish a preview; render returned null. Treat this as a shader/FBO/read-back or " +
                "handoff regression, not as missing hardware support.",
            file
        )
        return checkNotNull(file)
    }

    /**
     * Renders [graph] on the GPU and fails when the chain returns `null`.
     *
     * Skips only when [requireHeadlessGpu] proves the device lacks headless ES3. On a capable device
     * every graph must render, so each caller passes an effect [label] that names the graph in the
     * failure message instead of letting a regression hide behind a generic skip.
     */
    fun renderOnGpuRequired(
        source: EditorSource,
        graph: EditGraph,
        outDir: File,
        label: String
    ): File {
        val capabilities = requireHeadlessGpu()
        val rendered = renderOnGpuNullable(source, graph, outDir)
        assertNotNull(
            "$label: the device provides ${capabilities.summary}, so the offscreen GPU chain must " +
                "render this graph; renderNow returned null. Treat this as a shader/FBO/read-back " +
                "regression, not as missing hardware support.",
            rendered
        )
        return checkNotNull(rendered)
    }

    /** Renders the CPU reference preview, which production uses whenever the GPU path returns null. */
    fun renderOnCpu(bitmap: Bitmap, graph: EditGraph, outDir: File): File = File(
        checkNotNull(
            PreviewCacheWriter.writeBitmapPreviewTo(source = bitmap, graph = graph, outDir = outDir)
        ) { "The CPU preview writer produced no file in $outDir" }
    )

    /** Decodes a preview JPEG into a comparison-friendly frame and recycles the bitmap. */
    fun loadFrame(file: File): OffscreenFrame {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) {
            "Could not decode the rendered preview ${file.name}"
        }
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            OffscreenFrame(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    /** Stops the worker and re-arms the circuit breaker so test order cannot leak GPU policy. */
    fun resetGpuRenderer() = OffscreenPreviewRenderer.shutdown()

    /** Drops the shared source cache's own reference so its bitmaps do not outlive the test. */
    fun releaseCachedSources() = RecentEditorSourceCache.shared.onMemoryPressure()

    /** Asserts that [frame] shows [expected] at the normalized point `(u, v)`. */
    fun assertColorNear(
        label: String,
        frame: OffscreenFrame,
        u: Float,
        v: Float,
        expected: Int,
        tolerance: Int = MARKER_TOLERANCE
    ) {
        val actual = frame.averageColor(u, v)
        val redDelta = abs(red(actual) - red(expected))
        val greenDelta = abs(green(actual) - green(expected))
        val blueDelta = abs(blue(actual) - blue(expected))
        assertTrue(
            "$label: expected ${hex(expected)} at ($u, $v) but was ${hex(actual)} " +
                "(deltas r=$redDelta g=$greenDelta b=$blueDelta, tolerance=$tolerance)",
            redDelta <= tolerance && greenDelta <= tolerance && blueDelta <= tolerance
        )
    }

    /** Asserts every quadrant of [frame] still shows the expected marker colour. */
    fun assertQuadrants(
        label: String,
        frame: OffscreenFrame,
        expected: Map<Quadrant, Int> = QUADRANT_COLORS,
        tolerance: Int = MARKER_TOLERANCE
    ) {
        for ((quadrant, color) in expected) {
            assertColorNear("$label ${quadrant.name}", frame, quadrant.u, quadrant.v, color, tolerance)
        }
    }

    /**
     * Asserts the GPU frame reproduces the CPU frame within the accepted error budget.
     *
     * Both writers encode the same `1280px` frame at JPEG quality 92, so the mean channel delta is
     * the useful signal; the outlier bound only has to absorb JPEG ringing at the quadrant seams
     * and the `+/-1/255` dither of the GPU output stage.
     */
    fun assertFramesAgree(
        label: String,
        gpu: OffscreenFrame,
        cpu: OffscreenFrame,
        meanTolerance: Double,
        outlierFraction: Double,
        outlierDelta: Int
    ) {
        assertEquals("$label: frame size", cpu.width to cpu.height, gpu.width to gpu.height)
        val meanDelta = gpu.meanChannelDelta(cpu)
        assertTrue(
            "$label: mean channel delta $meanDelta/255 exceeds $meanTolerance/255",
            meanDelta <= meanTolerance
        )
        val outliers = gpu.fractionAbove(cpu, outlierDelta)
        assertTrue(
            "$label: ${(outliers * 100).format()}% of pixels differ by more than $outlierDelta/255, " +
                "above the ${(outlierFraction * 100).format()}% budget",
            outliers <= outlierFraction
        )
    }

    /** Asserts two renders are visibly different; used to prove a pass actually contributed. */
    fun assertFramesDiffer(label: String, first: OffscreenFrame, second: OffscreenFrame, minimumMean: Double) {
        assertEquals("$label: frame size", first.width to first.height, second.width to second.height)
        val meanDelta = first.meanChannelDelta(second)
        assertTrue("$label: mean channel delta $meanDelta/255 is not above $minimumMean/255", meanDelta > minimumMean)
    }

    private fun red(argb: Int): Int = (argb shr 16) and 0xFF
    private fun green(argb: Int): Int = (argb shr 8) and 0xFF
    private fun blue(argb: Int): Int = argb and 0xFF

    private fun hex(argb: Int): String = "#%06X".format(argb and 0x00FF_FFFF)

    private fun Double.format(): String = "%.2f".format(this)
}

/**
 * One decoded preview frame with the pixel statistics the assertions need.
 *
 * Keeping frames as `IntArray`s lets a test load the GPU and CPU previews, compare them and drop
 * them without holding several 1280px `Bitmap` objects alive at once.
 */
internal class OffscreenFrame(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {

    init {
        require(width > 0 && height > 0) { "A frame must be positive" }
        require(pixels.size == width * height) { "Pixel count does not match the frame" }
    }

    val longestEdge: Int get() = max(width, height)

    val aspect: Float get() = width.toFloat() / height.toFloat()

    /** Mean of the RGB channels inside a small box around `(u, v)`, both in `0..1`. */
    fun averageColor(u: Float, v: Float): Int {
        val radius = (min(width, height) / 20).coerceIn(1, 48)
        val centreX = (u * width).toInt()
        val centreY = (v * height).toInt()
        var red = 0L
        var green = 0L
        var blue = 0L
        var samples = 0L
        for (y in (centreY - radius)..(centreY + radius)) {
            if (y !in 0 until height) continue
            for (x in (centreX - radius)..(centreX + radius)) {
                if (x !in 0 until width) continue
                val pixel = pixels[y * width + x]
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                samples++
            }
        }
        if (samples == 0L) return 0
        return (0xFF shl 24) or
            ((red / samples).toInt() shl 16) or
            ((green / samples).toInt() shl 8) or
            (blue / samples).toInt()
    }

    /** Mean value of the channel selected by [shift] (`16` red, `8` green, `0` blue). */
    fun meanChannel(shift: Int): Double {
        var sum = 0L
        for (pixel in pixels) {
            sum += (pixel shr shift) and 0xFF
        }
        return sum.toDouble() / pixels.size
    }

    /** Mean per-pixel difference between the largest and the smallest channel, i.e. saturation. */
    fun meanChannelSpread(): Double {
        var sum = 0L
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            sum += maxOf(red, green, blue) - minOf(red, green, blue)
        }
        return sum.toDouble() / pixels.size
    }

    /** Mean absolute per-channel difference to [other], in `0..255`. */
    fun meanChannelDelta(other: OffscreenFrame): Double {
        require(width == other.width && height == other.height) {
            "Frames must have the same size to compare"
        }
        var sum = 0L
        for (index in pixels.indices) {
            val left = pixels[index]
            val right = other.pixels[index]
            sum += abs(((left shr 16) and 0xFF) - ((right shr 16) and 0xFF)).toLong()
            sum += abs(((left shr 8) and 0xFF) - ((right shr 8) and 0xFF)).toLong()
            sum += abs((left and 0xFF) - (right and 0xFF)).toLong()
        }
        return sum.toDouble() / (pixels.size * CHANNELS)
    }

    /** Fraction of pixels whose largest channel difference to [other] exceeds [threshold]. */
    fun fractionAbove(other: OffscreenFrame, threshold: Int): Double {
        require(width == other.width && height == other.height) {
            "Frames must have the same size to compare"
        }
        var above = 0
        for (index in pixels.indices) {
            val left = pixels[index]
            val right = other.pixels[index]
            val delta = maxOf(
                abs(((left shr 16) and 0xFF) - ((right shr 16) and 0xFF)),
                abs(((left shr 8) and 0xFF) - ((right shr 8) and 0xFF)),
                abs((left and 0xFF) - (right and 0xFF))
            )
            if (delta > threshold) above++
        }
        return above.toDouble() / pixels.size
    }

    private companion object {
        const val CHANNELS = 3
    }
}
