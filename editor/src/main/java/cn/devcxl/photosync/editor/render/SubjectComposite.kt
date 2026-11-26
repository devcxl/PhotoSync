package cn.devcxl.photosync.editor.render

import kotlin.math.floor

import cn.devcxl.photosync.editor.domain.BandGuidedFilter
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ForegroundColorEstimator
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.vision.SubjectImage
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Source-space subject composite consumed by the preview, the bake and the CPU preview writer.
 *
 * [alpha] is the guided-filter-refined cut-out, [foreground] the FMGE-decontaminated foreground and
 * [background] the matching background estimate. All planes live in **upright pre-crop source
 * space**, so every renderer maps them through the same Pass A transform as the pixels.
 *
 * @property width Composite width in pixels.
 * @property height Composite height in pixels.
 * @property alpha Row-major straight alpha.
 * @property foreground Interleaved RGB foreground colours.
 * @property background Interleaved RGB background colours.
 */
internal data class SubjectComposite(
    val width: Int,
    val height: Int,
    val alpha: FloatArray,
    val foreground: FloatArray,
    val background: FloatArray
) {

    init {
        require(width > 0 && height > 0) { "Subject composite dimensions must be positive" }
        require(alpha.size == width * height) { "Subject composite alpha must match its dimensions" }
        require(foreground.size == width * height * CHANNELS) {
            "Subject composite foreground must be RGB"
        }
        require(background.size == width * height * CHANNELS) {
            "Subject composite background must be RGB"
        }
    }

    /** Bilinear alpha sample at normalized [u], [v]. */
    fun alphaAt(u: Float, v: Float): Float = samplePlane(alpha, u, v, 1)

    /** Bilinear foreground/background sample, writing RGB into [out]. */
    fun foregroundAt(u: Float, v: Float, out: FloatArray) = sampleRgb(foreground, u, v, out)

    /** Bilinear background sample, writing RGB into [out]. */
    fun backgroundAt(u: Float, v: Float, out: FloatArray) = sampleRgb(background, u, v, out)

    /**
     * Packs the composite into `RGBA8` texels where `RGB` is the foreground and `A` the alpha.
     *
     * That layout is exactly what the Pass D shader needs: one texture fetch returns both the
     * cut-out and the decontaminated colour that must survive the later LUT/grain/vignette stages.
     * The integer is `0xRRGGBBAA`.
     */
    fun toRgba8(): IntArray = IntArray(width * height) { index ->
        val red = toByte(foreground[index * CHANNELS])
        val green = toByte(foreground[index * CHANNELS + 1])
        val blue = toByte(foreground[index * CHANNELS + 2])
        val alphaByte = toByte(alpha[index])
        // RGBA in the high-to-low byte order the texture upload reads back.
        (red shl 24) or (green shl 16) or (blue shl 8) or alphaByte
    }

    private fun samplePlane(plane: FloatArray, u: Float, v: Float, stride: Int): Float {
        val coordinates = BilinearCoordinates.of(u, v, width, height)
        val p00 = plane[coordinates.index00 * stride]
        val p10 = plane[coordinates.index10 * stride]
        val p01 = plane[coordinates.index01 * stride]
        val p11 = plane[coordinates.index11 * stride]
        val top = p00 + (p10 - p00) * coordinates.fx
        val bottom = p01 + (p11 - p01) * coordinates.fx
        return top + (bottom - top) * coordinates.fy
    }

    private fun sampleRgb(plane: FloatArray, u: Float, v: Float, out: FloatArray) {
        val coordinates = BilinearCoordinates.of(u, v, width, height)
        for (channel in 0 until CHANNELS) {
            val p00 = plane[coordinates.index00 * CHANNELS + channel]
            val p10 = plane[coordinates.index10 * CHANNELS + channel]
            val p01 = plane[coordinates.index01 * CHANNELS + channel]
            val p11 = plane[coordinates.index11 * CHANNELS + channel]
            val top = p00 + (p10 - p00) * coordinates.fx
            val bottom = p01 + (p11 - p01) * coordinates.fx
            out[channel] = (top + (bottom - top) * coordinates.fy).coerceIn(0.0f, 1.0f)
        }
    }

    private data class BilinearCoordinates(
        val index00: Int,
        val index10: Int,
        val index01: Int,
        val index11: Int,
        val fx: Float,
        val fy: Float
    ) {
        companion object {
            fun of(u: Float, v: Float, width: Int, height: Int): BilinearCoordinates {
                val x = (u.coerceIn(0.0f, 1.0f) * (width - 1)).coerceIn(0.0f, (width - 1).toFloat())
                val y = (v.coerceIn(0.0f, 1.0f) * (height - 1)).coerceIn(0.0f, (height - 1).toFloat())
                val x0 = floor(x).toInt()
                val y0 = floor(y).toInt()
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val y1 = (y0 + 1).coerceAtMost(height - 1)
                return BilinearCoordinates(
                    index00 = y0 * width + x0,
                    index10 = y0 * width + x1,
                    index01 = y1 * width + x0,
                    index11 = y1 * width + x1,
                    fx = x - x0,
                    fy = y - y0
                )
            }
        }
    }

    private companion object {
        const val CHANNELS = 3

        fun toByte(value: Float): Int =
            (value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)
    }
}

/**
 * Builds a [SubjectComposite] from one upright source image and the MODNet mask.
 *
 * The mask is first resampled to the preview proxy, then edge-refined by [BandGuidedFilter] using
 * the proxy luma as guide, and finally decontaminated by [ForegroundColorEstimator]. A failure to
 * allocate the float planes degrades to a feathered alpha and `F = I`; it never aborts the editor or
 * a bake.
 */
internal object SubjectCompositeFactory {

    /** Analysis and preview proxy cap; the FMGE initialization budget is quoted for this size. */
    const val PROXY_MAX_EDGE_PX = 1024

    /**
     * Builds the composite for [image].
     *
     * @param image Upright source pixels at any resolution; it is downscaled internally.
     * @param mask MODNet mask in the same pre-crop space.
     */
    fun build(
        image: SubjectImage,
        mask: SubjectMask,
        maxEdge: Int = PROXY_MAX_EDGE_PX
    ): SubjectComposite {
        val proxy = image.scaledToMaxEdge(maxEdge)
        val width = proxy.width
        val height = proxy.height
        val resampled = mask.resampled(width, height)
        val rgb = FloatArray(width * height * CHANNELS)
        val guide = FloatArray(width * height)
        for (index in 0 until width * height) {
            val red = proxy.redAt(index)
            val green = proxy.greenAt(index)
            val blue = proxy.blueAt(index)
            rgb[index * CHANNELS] = red
            rgb[index * CHANNELS + 1] = green
            rgb[index * CHANNELS + 2] = blue
            guide[index] = LUMA_R * red + LUMA_G * green + LUMA_B * blue
        }
        return try {
            val refined = BandGuidedFilter.refine(guide, resampled.alpha, width, height)
            val estimate = ForegroundColorEstimator.initialize(rgb, refined, width, height)
            SubjectComposite(width, height, refined, estimate.foreground, estimate.background)
        } catch (oom: OutOfMemoryError) {
            degradedComposite(rgb, resampled.alpha, width, height)
        }
    }

    /**
     * Degraded composite used when the guided filter or FMGE cannot allocate.
     *
     * The model alpha is bilinearly upsampled and softened only in its transition band; the original
     * pixels become the foreground so no colour cast is introduced.
     */
    fun degradedComposite(
        rgb: FloatArray,
        alpha: FloatArray,
        width: Int,
        height: Int
    ): SubjectComposite {
        val feathered = try {
            BandGuidedFilter.feather(alpha, width, height, BandGuidedFilter.scaledRadius(width) / 2)
        } catch (oom: OutOfMemoryError) {
            alpha.copyOf()
        }
        return SubjectComposite(
            width = width,
            height = height,
            alpha = feathered,
            foreground = try {
                rgb.copyOf()
            } catch (oom: OutOfMemoryError) {
                FloatArray(rgb.size)
            },
            background = FloatArray(rgb.size)
        )
    }

    private fun SubjectImage.redAt(index: Int): Float =
        ((argb[index] ushr 16) and 0xFF) / 255.0f

    private fun SubjectImage.greenAt(index: Int): Float =
        ((argb[index] ushr 8) and 0xFF) / 255.0f

    private fun SubjectImage.blueAt(index: Int): Float =
        (argb[index] and 0xFF) / 255.0f

    private const val CHANNELS = 3
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
}

/**
 * Applies a [SubjectComposite] to a CPU-rendered `ARGB` preview frame.
 *
 * This is the reference implementation used by the cached thumbnail when the offscreen GPU chain is
 * unavailable. It maps every output pixel through the same Pass A inverse homography and warp as
 * [GeometryCpuResampler], samples the source-space composite, and
 * composites exactly like the Pass D shader:
 * - [SubjectBackgroundMode.SOLID] → `a F + (1 - a) B_solid`, applied before Pass D;
 * - [SubjectBackgroundMode.TRANSPARENT] → `F` with `A = alpha`, so the caller can run Pass D on
 *   the foreground only and draw the canvas checkerboard afterwards.
 */
internal object SubjectCpuComposer {

    private const val CHECKER_LIGHT = 0x2E2E2E
    private const val CHECKER_DARK = 0x3C3C3C
    private const val CHECKER_CELL_PX = 12

    /**
     * Composites the subject foreground into [pixels], returning a new buffer.
     *
     * Transparent subjects keep their alpha in the `A` byte and write the decontaminated foreground
     * into `RGB`; Pass D preserves that alpha, so [drawCheckerboard] can run after the effects.
     */
    fun applyForeground(
        pixels: IntArray,
        width: Int,
        height: Int,
        graph: EditGraph,
        composite: SubjectComposite?,
        inverseMatrix: Mat3,
        warpField: WarpDisplacementField? = null
    ): IntArray {
        val subject = graph.subject
        if (subject.bgMode == SubjectBackgroundMode.ORIGINAL || composite == null) return pixels
        val output = IntArray(pixels.size)
        val foreground = FloatArray(3)
        val solid = argbToRgb(subject.bgColorArgb)
        for (row in 0 until height) {
            val v = (row + 0.5f) / height
            for (column in 0 until width) {
                val index = row * width + column
                val u = (column + 0.5f) / width
                val mapped = inverseMatrix.map(u, v)
                val warped = warpField?.let {
                    val displacement = it.displacementAt(mapped.u, mapped.v)
                    Uv(
                        mapped.u + displacement.u,
                        mapped.v + displacement.v
                    )
                } ?: mapped
                val alpha = composite.alphaAt(warped.u, warped.v)
                composite.foregroundAt(warped.u, warped.v, foreground)
                val bodyWeight = smoothStep(0.85f, 1.0f, alpha)
                if (bodyWeight > 0.0f) {
                    val source = pixels[index]
                    foreground[0] = mixFloat(foreground[0], ((source ushr 16) and 0xFF) / 255.0f, bodyWeight)
                    foreground[1] = mixFloat(foreground[1], ((source ushr 8) and 0xFF) / 255.0f, bodyWeight)
                    foreground[2] = mixFloat(foreground[2], (source and 0xFF) / 255.0f, bodyWeight)
                }
                output[index] = if (subject.bgMode == SubjectBackgroundMode.TRANSPARENT) {
                    (toByte(alpha) shl 24) or packRgb(foreground)
                } else {
                    mix(foreground, solid, alpha)
                }
            }
        }
        return output
    }

    /**
     * Draws the canvas checkerboard behind the alpha-carrying [pixels] produced by
     * [applyForeground].
     *
     * Must run **after** Pass D: the checkerboard is presentation-only, so LUT/grain/vignette may
     * never darken it. Fully opaque buffers are returned unchanged.
     */
    fun drawCheckerboard(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val checkerLight = argbToRgb(0xFF000000.toInt() or CHECKER_LIGHT)
        val checkerDark = argbToRgb(0xFF000000.toInt() or CHECKER_DARK)
        val foreground = FloatArray(3)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val index = row * width + column
                val pixel = pixels[index]
                val alpha = (pixel ushr 24) / 255.0f
                if (alpha >= 1.0f) continue
                foreground[0] = ((pixel ushr 16) and 0xFF) / 255.0f
                foreground[1] = ((pixel ushr 8) and 0xFF) / 255.0f
                foreground[2] = (pixel and 0xFF) / 255.0f
                val light = ((column / CHECKER_CELL_PX) + (row / CHECKER_CELL_PX)) % 2 == 0
                pixels[index] = mix(foreground, if (light) checkerLight else checkerDark, alpha)
            }
        }
    }

    private fun packRgb(rgb: FloatArray): Int =
        (toByte(rgb[0]) shl 16) or (toByte(rgb[1]) shl 8) or toByte(rgb[2])

    private fun mixFloat(from: Float, to: Float, weight: Float): Float =
        from + (to - from) * weight

    /** Hermite smoothstep matching the GLSL `smoothstep(edge0, edge1, x)`. */
    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return if (value >= edge1) 1.0f else 0.0f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }

    private fun mix(foreground: FloatArray, background: FloatArray, alpha: Float): Int {
        val red = foreground[0] * alpha + background[0] * (1.0f - alpha)
        val green = foreground[1] * alpha + background[1] * (1.0f - alpha)
        val blue = foreground[2] * alpha + background[2] * (1.0f - alpha)
        return 0xFF000000.toInt() or (toByte(red) shl 16) or (toByte(green) shl 8) or toByte(blue)
    }

    private fun argbToRgb(argb: Int): FloatArray = floatArrayOf(
        ((argb ushr 16) and 0xFF) / 255.0f,
        ((argb ushr 8) and 0xFF) / 255.0f,
        (argb and 0xFF) / 255.0f
    )

    private fun toByte(value: Float): Int =
        (value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)
}
