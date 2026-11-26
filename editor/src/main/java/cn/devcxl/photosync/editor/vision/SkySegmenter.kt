package cn.devcxl.photosync.editor.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.BandGuidedFilter
import cn.devcxl.photosync.editor.domain.OklabColorMath
import cn.devcxl.photosync.editor.domain.ToneShaderMath

/**
 * Continuous `0..1` sky probability map produced by a [SkySegmenter].
 *
 * Values are straight probabilities on the upright pre-crop source grid: `1` is sky, `0` is
 * foreground and the transition band carries roof edges, ridges and bare branches.
 *
 * @property width Mask width in pixels.
 * @property height Mask height in pixels.
 * @property probability Row-major sky probabilities; length is [width] x [height].
 * @property coverageRatio Fraction of the `1024px` proxy whose probability is at least `0.5`.
 */
data class SkyMask(
    val width: Int,
    val height: Int,
    val probability: FloatArray,
    val coverageRatio: Float
) {

    init {
        require(width > 0 && height > 0) { "Sky mask dimensions must be positive" }
        require(probability.size == width * height) { "Sky mask buffer must match its dimensions" }
    }

    /**
     * Bilinear sample at normalized [u], [v], clamped to the mask border.
     *
     * @return The probability in `[0, 1]`, or `0` for a non-finite coordinate.
     */
    fun sample(u: Float, v: Float): Float {
        if (!u.isFinite() || !v.isFinite()) return 0.0f
        val x = (u.coerceIn(0.0f, 1.0f) * (width - 1)).coerceIn(0.0f, (width - 1).toFloat())
        val y = (v.coerceIn(0.0f, 1.0f) * (height - 1)).coerceIn(0.0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val top = sampleAt(y0 * width + x0, y0 * width + x1, fx)
        val bottom = sampleAt(y1 * width + x0, y1 * width + x1, fx)
        return (top + (bottom - top) * fy).coerceIn(0.0f, 1.0f)
    }

    /** Resamples this mask onto [targetWidth] x [targetHeight] with bilinear interpolation. */
    fun resampled(targetWidth: Int, targetHeight: Int): SkyMask {
        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        if (safeWidth == width && safeHeight == height) return this
        val out = FloatArray(safeWidth * safeHeight)
        for (row in 0 until safeHeight) {
            val v = (row + 0.5f) / safeHeight
            for (column in 0 until safeWidth) {
                val u = (column + 0.5f) / safeWidth
                out[row * safeWidth + column] = sample(u, v)
            }
        }
        return SkyMask(safeWidth, safeHeight, out, coverageOf(out))
    }

    private fun sampleAt(left: Int, right: Int, weight: Float): Float =
        probability[left] + (probability[right] - probability[left]) * weight

    /** Mean of the hard `>= 0.5` sky decision, the metric the `< 3 %` warning uses. */
    fun computeCoverageRatio(): Float = coverageOf(probability)

    companion object {

        /** Sky coverage below this fraction shows the "no obvious sky" hint instead of painting. */
        const val MIN_USABLE_COVERAGE = 0.03f

        internal fun coverageOf(probability: FloatArray): Float {
            if (probability.isEmpty()) return 0.0f
            var count = 0
            for (value in probability) {
                if (value >= 0.5f) count++
            }
            return count.toFloat() / probability.size
        }
    }
}

/**
 * Outcome of one sky segmentation.
 *
 * @property mask Continuous probability map in upright pre-crop source space.
 * @property hasSky True when the mask covers at least [SkyMask.MIN_USABLE_COVERAGE] of the frame.
 */
data class SkySegmentation(
    val mask: SkyMask,
    val hasSky: Boolean
)

/**
 * Produces a [SkyMask] for one image on the camera-roll device.
 *
 * The interface is model-agnostic so a future lightweight ONNX sky model can be hot-plugged through
 * the same `ModelDownloadManager` without touching the renderer, the bake or the editor UI. The
 * zero-model [ClassicSkySegmenter] is the shipped v1 implementation and needs no download at all.
 */
interface SkySegmenter {

    /**
     * Segments [image], which must already be upright.
     *
     * @return The mask, or a recoverable failure (for example an allocation failure).
     */
    fun segment(image: SubjectImage): Result<SkySegmentation>
}

/**
 * Zero-model sky segmenter: top-seeded region growth over OKLCH brightness/chroma and blue/warm sky
 * hue evidence, a vertical spatial prior, and one guided-filter pass that snaps the probability
 * field onto roofs, ridges and bare branches.
 *
 * The whole analysis runs on a `1024px` proxy ([proxyMaxEdge]) because the mask is a smooth
 * probability field; the renderer resamples it through the same Pass A transform as the pixels.
 * `radius = 8` at `1024px` and `epsilon = 1e-3` are the frozen v1 parameters of the P3 spec.
 */
class ClassicSkySegmenter(
    private val proxyMaxEdge: Int = DEFAULT_PROXY_MAX_EDGE,
    private val guidedRadius: Int = DEFAULT_GUIDED_RADIUS,
    private val guidedEpsilon: Float = DEFAULT_GUIDED_EPSILON
) : SkySegmenter {

    private val lab = FloatArray(3)
    private val linear = FloatArray(3)

    override fun segment(image: SubjectImage): Result<SkySegmentation> = try {
        Result.success(segmentInternal(image))
    } catch (oom: OutOfMemoryError) {
        Result.failure(oom)
    }

    private fun segmentInternal(image: SubjectImage): SkySegmentation {
        val proxy = image.scaledToMaxEdge(proxyMaxEdge)
        val width = proxy.width
        val height = proxy.height
        val count = width * height
        val luma = FloatArray(count)
        val score = FloatArray(count)
        for (index in 0 until count) {
            val pixel = proxy.argb[index]
            val red = ((pixel ushr RED_SHIFT) and 0xFF) / 255.0f
            val green = ((pixel ushr GREEN_SHIFT) and 0xFF) / 255.0f
            val blue = (pixel and 0xFF) / 255.0f
            linear[0] = ExposureShaderMath.decodeSrgb(red)
            linear[1] = ExposureShaderMath.decodeSrgb(green)
            linear[2] = ExposureShaderMath.decodeSrgb(blue)
            OklabColorMath.linearSrgbToOklab(linear, lab)
            luma[index] = lab[0].coerceIn(0.0f, 1.0f)
            score[index] = skyScore(
                redness = red,
                greenness = green,
                blueness = blue,
                lightness = luma[index],
                chroma = sqrt(lab[1] * lab[1] + lab[2] * lab[2]),
                hueRad = atan2(lab[2], lab[1]),
                vertical = verticalPrior(index / width, height)
            )
        }
        val accepted = growFromTop(score, luma, width, height)
        val seeded = FloatArray(count) { index ->
            if (accepted[index]) max(score[index], GROWN_FLOOR) else 0.0f
        }
        // The fast guided filter computes its coefficients on a subsampled grid and evaluates them
        // against the full `1024px` luma guide. It keeps the architecture, ridge and branch edges of
        // the proxy sharp without the twelve full-size planes of the textbook implementation.
        val refined = BandGuidedFilter.refine(
            guide = luma,
            alpha = seeded,
            width = width,
            height = height,
            radius = guidedRadius,
            epsilon = guidedEpsilon
        )
        val probability = FloatArray(count) { index ->
            val capped = refined[index] * bottomGate(index / width, height)
            capped.coerceIn(0.0f, 1.0f)
        }
        val coverage = SkyMask.coverageOf(probability)
        val mask = SkyMask(width, height, probability, coverage)
        return SkySegmentation(mask = mask, hasSky = coverage >= SkyMask.MIN_USABLE_COVERAGE)
    }

    /**
     * Sky evidence of one pixel before the vertical prior.
     *
     * Three independent cues are combined because one alone fails on real frames: a pale overcast
     * sky has almost no chroma, a deep blue noon sky is darker than a white wall, and a sunset is
     * warm rather than blue.
     */
    private fun skyScore(
        redness: Float,
        greenness: Float,
        blueness: Float,
        lightness: Float,
        chroma: Float,
        hueRad: Float,
        vertical: Float
    ): Float {
        val brightness = ToneShaderMath.smoothStep(0.30f, 0.72f, lightness)
        val pale = (1.0f - ToneShaderMath.smoothStep(0.03f, 0.14f, chroma)) *
            ToneShaderMath.smoothStep(0.42f, 0.80f, lightness)
        val blueDominance = ((blueness - max(redness, greenness)) / 0.22f).coerceIn(0.0f, 1.0f)
        val blueSky = blueDominance * (0.55f + 0.45f * brightness)
        val warmAlignment = (0.5f + 0.5f * cos(hueRad - WARM_HUE_RAD)).coerceIn(0.0f, 1.0f)
        val warmSky = warmAlignment * warmAlignment *
            ToneShaderMath.smoothStep(0.42f, 0.78f, lightness) *
            (1.0f - ToneShaderMath.smoothStep(0.08f, 0.22f, chroma))
        val evidence = max(pale, max(blueSky, warmSky))
        return (evidence * vertical).coerceIn(0.0f, 1.0f)
    }

    /** The sky prior: full weight in the upper half, tapering to zero near the bottom edge. */
    private fun verticalPrior(row: Int, height: Int): Float {
        val v = (row + 0.5f) / height
        return 1.0f - ToneShaderMath.smoothStep(0.58f, 0.94f, v)
    }

    /** The bottom 8 % may never be sky, which removes reflections and bright ground from the growth. */
    private fun bottomGate(row: Int, height: Int): Float {
        val v = (row + 0.5f) / height
        return 1.0f - ToneShaderMath.smoothStep(0.92f, 1.0f, v)
    }

    /**
     * Grows one connected region from the top rows.
     *
     * Seeds are strong candidates in the top [seed] rows. A neighbour joins only when it is
     * reasonably sky-like **and** close in lightness to the pixel that reached it; that second test
     * is what keeps a white facade or a grey road from being absorbed into a bright sky.
     */
    private fun growFromTop(
        score: FloatArray,
        luma: FloatArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val accepted = BooleanArray(score.size)
        val stack = IntArray(score.size)
        var top = 0
        val seedRows = max(2, height / 10)
        for (row in 0 until min(seedRows, height)) {
            for (column in 0 until width) {
                val index = row * width + column
                if (score[index] >= SEED_THRESHOLD) {
                    accepted[index] = true
                    stack[top++] = index
                }
            }
        }
        while (top > 0) {
            val index = stack[--top]
            val row = index / width
            val column = index % width
            if (column > 0) top = visit(index - 1, index, score, luma, accepted, stack, top)
            if (column + 1 < width) top = visit(index + 1, index, score, luma, accepted, stack, top)
            if (row > 0) top = visit(index - width, index, score, luma, accepted, stack, top)
            if (row + 1 < height) top = visit(index + width, index, score, luma, accepted, stack, top)
        }
        return accepted
    }

    private fun visit(
        candidate: Int,
        source: Int,
        score: FloatArray,
        luma: FloatArray,
        accepted: BooleanArray,
        stack: IntArray,
        top: Int
    ): Int {
        if (accepted[candidate]) return top
        if (score[candidate] < GROW_THRESHOLD) return top
        if (abs(luma[candidate] - luma[source]) > GROW_LUMA_TOLERANCE) return top
        accepted[candidate] = true
        stack[top] = candidate
        return top + 1
    }

    companion object {
        /** Longest edge of the analysis proxy. */
        const val DEFAULT_PROXY_MAX_EDGE = 1024

        /** Guided-filter radius frozen by the P3 specification. */
        const val DEFAULT_GUIDED_RADIUS = 8

        /** Guided-filter regularisation frozen by the P3 specification. */
        const val DEFAULT_GUIDED_EPSILON = 1e-3f

        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val WARM_HUE_RAD = 1.15f
        private const val SEED_THRESHOLD = 0.45f
        private const val GROW_THRESHOLD = 0.20f
        private const val GROW_LUMA_TOLERANCE = 0.22f
        private const val GROWN_FLOOR = 0.35f
    }
}
