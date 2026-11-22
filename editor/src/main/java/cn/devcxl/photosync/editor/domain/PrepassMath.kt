package cn.devcxl.photosync.editor.domain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The 1024px full-image Pre-pass that every spatial-detail parameter depends on.
 *
 * A single pass over the Post-`Pass B` proxy frame extracts the three globally coupled quantities
 * that must never be recomputed per tile, otherwise a column-band Bake would show seams:
 * 1. **Atmospheric light `A`** — the per-channel maximum of the brightest `0.1%` dark-channel pixels.
 * 2. **Coarse transmission `t_low`** — `1 - omega * min_c(I_c / A_c)` on a `256 x 256` grid, smoothed
 *    by a radius-8 guided filter so the Dehaze stage only needs one bilinear fetch per pixel.
 * 3. **Low-frequency luminance guides** — the `512px` (`0.3%` short edge) and `128px` (`2.0%` short
 *    edge) layers packed for the Texture and Clarity high-passes.
 *
 * Everything here is pure Kotlin: the renderer feeds it a read-back `GL_RGBA8` frame and the preview
 * cache writer feeds it a downsampled `Bitmap`, and both get the exact same numbers.
 */
object PrepassMath {

    /** Side of the analysis grid the dark channel and coarse transmission live on. */
    const val ANALYSIS_GRID = 256

    /** The `7 x 7` minimum filter of the dark-channel prior. */
    const val DARK_CHANNEL_RADIUS = 3

    /** Fraction of the darkest-pixel candidates whose brightness defines `A`. */
    const val ATMOSPHERIC_PERCENTILE = 0.001f

    /** Classically `0.95`: keeping a little haze is what stops the sky from banding. */
    const val DEFAULT_OMEGA = 0.95f

    /** Hard upper bound on [DEFAULT_OMEGA], mirroring the shader contract. */
    const val MAX_OMEGA = 0.95f

    /** Transmission lower bound applied in Pass C, so a pitch-black sky cannot divide by zero. */
    const val TRANSMISSION_FLOOR = 0.1f

    /** `A` never drops below this, which keeps `I_c / A_c` numerically sane in a dark frame. */
    const val ATMOSPHERIC_FLOOR = 0.10f

    /** Guided-filter radius used to smooth the coarse transmission. */
    const val GUIDED_RADIUS = 8

    /** Guided-filter regularisation of the transmission smoothing. */
    const val GUIDED_EPSILON = 1e-3f

    private const val MIN_GRID = 8
    private const val PACKED_CHANNELS = 4
    private const val CHANNELS = 3

    /**
     * Immutable Pre-pass result: the global atmospheric light, the coarse transmission, the two
     * luminance guide layers and the interleaved `RGBA16F` payload uploaded as `uPrepassTex`.
     */
    class Analysis(
        /** Per-channel global atmospheric light `A`, each in `[ATMOSPHERIC_FLOOR, 1]`. */
        val atmosphericLight: FloatArray,
        /** Guided-smoothed coarse transmission, `gridSize * gridSize`, row-major. */
        val transmissionLow: FloatArray,
        /** Clarity guide (`2.0%` short edge) on the analysis grid. */
        val clarityBase: FloatArray,
        /** Texture guide (`0.3%` short edge) on the analysis grid. */
        val textureBase: FloatArray,
        /** Side of the analysis grid actually used for this frame. */
        val gridSize: Int
    ) {

        /**
         * Interleaved `RGBA` payload: `r = clarityBase`, `g = textureBase`, `b = transmissionLow`,
         * `a = 1`. One texture fetch therefore serves all three Pass C lookups.
         */
        val packedGuides: FloatArray = FloatArray(gridSize * gridSize * PACKED_CHANNELS) { index ->
            val cell = index / PACKED_CHANNELS
            when (index % PACKED_CHANNELS) {
                0 -> clarityBase[cell]
                1 -> textureBase[cell]
                2 -> transmissionLow[cell]
                else -> 1.0f
            }
        }

        /** Bilinear sample of the coarse transmission at normalized `u, v`. */
        fun transmissionAt(u: Float, v: Float): Float =
            ImageFilters.sampleBilinear(transmissionLow, gridSize, gridSize, u, v)

        /** Bilinear sample of the Clarity guide at normalized `u, v`. */
        fun clarityBaseAt(u: Float, v: Float): Float =
            ImageFilters.sampleBilinear(clarityBase, gridSize, gridSize, u, v)

        /** Bilinear sample of the Texture guide at normalized `u, v`. */
        fun textureBaseAt(u: Float, v: Float): Float =
            ImageFilters.sampleBilinear(textureBase, gridSize, gridSize, u, v)
    }

    /**
     * Analyses a display-domain `Pass B` frame.
     *
     * @param display Interleaved sRGB `RGB` samples in `[0, 1]`, row-major.
     * @param width Frame width in pixels; the caller is expected to have already fitted the frame
     *   inside `1024px` so this Pre-pass stays cheap.
     * @param omega Haze retention factor, clamped to [MAX_OMEGA].
     */
    fun analyze(
        display: FloatArray,
        width: Int,
        height: Int,
        omega: Float = DEFAULT_OMEGA
    ): Analysis {
        require(display.size == width * height * CHANNELS) {
            "A display frame needs ${width * height * CHANNELS} samples, got ${display.size}"
        }
        val grid = min(ANALYSIS_GRID, min(width, height)).coerceAtLeast(MIN_GRID)
        val small = ImageFilters.resampleRgb(display, width, height, grid, grid)
        val atmosphere = atmosphericLight(small, darkChannel(small, grid), grid)
        val transmission = smoothTransmission(small, atmosphere, grid, omega)
        val guides = LowFrequencyPyramid.build(
            ImageFilters.luminance(display),
            width,
            height,
            grid
        )
        return Analysis(
            atmosphericLight = atmosphere,
            transmissionLow = transmission,
            clarityBase = guides.clarityBase,
            textureBase = guides.textureBase,
            gridSize = grid
        )
    }

    private fun darkChannel(rgb: FloatArray, grid: Int): FloatArray {
        val minimum = FloatArray(grid * grid) { cell ->
            val base = cell * CHANNELS
            min(rgb[base], min(rgb[base + 1], rgb[base + 2]))
        }
        return ImageFilters.minFilter(minimum, grid, grid, DARK_CHANNEL_RADIUS)
    }

    private fun atmosphericLight(rgb: FloatArray, dark: FloatArray, grid: Int): FloatArray {
        val cells = grid * grid
        val count = ceil(cells * ATMOSPHERIC_PERCENTILE).toInt().coerceIn(1, cells)
        val candidates = dark.indices.sortedByDescending { dark[it] }.take(count)
        val light = FloatArray(CHANNELS) { ATMOSPHERIC_FLOOR }
        for (cell in candidates) {
            for (channel in 0 until CHANNELS) {
                light[channel] = max(light[channel], rgb[cell * CHANNELS + channel])
            }
        }
        return FloatArray(CHANNELS) { light[it].coerceIn(ATMOSPHERIC_FLOOR, 1.0f) }
    }

    private fun coarseTransmission(
        rgb: FloatArray,
        atmosphere: FloatArray,
        grid: Int,
        omega: Float
    ): FloatArray {
        val clampedOmega = omega.coerceIn(0.0f, MAX_OMEGA)
        return FloatArray(grid * grid) { cell ->
            val base = cell * CHANNELS
            val ratio = min(
                rgb[base] / atmosphere[0],
                min(rgb[base + 1] / atmosphere[1], rgb[base + 2] / atmosphere[2])
            )
            (1.0f - clampedOmega * ratio).coerceIn(0.0f, 1.0f)
        }
    }

    private fun smoothTransmission(
        rgb: FloatArray,
        atmosphere: FloatArray,
        grid: Int,
        omega: Float
    ): FloatArray {
        val coarse = coarseTransmission(rgb, atmosphere, grid, omega)
        val guide = ImageFilters.luminance(rgb)
        val smoothed = GuidedFilter.smooth(guide, coarse, grid, grid, GUIDED_RADIUS, GUIDED_EPSILON)
        return FloatArray(smoothed.size) { smoothed[it].coerceIn(0.0f, 1.0f) }
    }
}
