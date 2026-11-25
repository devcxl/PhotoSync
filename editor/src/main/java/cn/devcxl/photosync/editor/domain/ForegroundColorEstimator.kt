package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Low-frequency foreground and background colour estimate of one image.
 *
 * @property width Grid width in pixels.
 * @property height Grid height in pixels.
 * @property foreground Interleaved RGB foreground colours, three floats per pixel.
 * @property background Interleaved RGB background colours, three floats per pixel.
 */
internal data class ForegroundEstimate(
    val width: Int,
    val height: Int,
    val foreground: FloatArray,
    val background: FloatArray
) {

    /** Three-channel sample of the foreground at pixel [index]. */
    fun foregroundAt(index: Int, channel: Int): Float = foreground[index * CHANNELS + channel]

    /** Three-channel sample of the background at pixel [index]. */
    fun backgroundAt(index: Int, channel: Int): Float = background[index * CHANNELS + channel]

    internal companion object {
        const val CHANNELS = 3
    }
}

/**
 * Fast Multi-Level Foreground Estimation (FMGE, Germer et al., arXiv:2006.14970).
 *
 * The compositing equation `I = alpha F + (1 - alpha) B` leaves `F` and `B` underdetermined on
 * every mixed pixel. FMGE solves a tiny local system instead of a global matting Laplacian: over a
 * local neighbourhood the colours are modelled as `I_i = B + alpha_i (F - B)`, which is a straight
 * line in `alpha`. A weighted 2x2 normal equation yields the intercept `B` and the difference
 * `F - B` and therefore both colours for every mixed pixel.
 *
 * The implementation follows the production shape described in
 * `docs/research/2026-11-26-ort-android-and-matting-models.md` §5.5:
 * 1. [initialize] builds a full pyramid on the `<= 1024px` proxy and solves from coarse to fine;
 * 2. [refineBand] seeds a bake band from that low-frequency estimate and runs only two local rounds,
 *    touching just the `alpha in (0.02, 0.98)` transition band.
 *
 * Parameters keep the paper defaults: regularisation `EPSILON_R = 5e-3`, alpha-gradient weight
 * `OMEGA = 0.1`, ten iterations at `<= 32px` and two above it. Only three RGB planes are alive per
 * level, which is the `~10 floats/px` budget quoted for FMGE.
 */
internal object ForegroundColorEstimator {

    /** Paper regularisation `epsilon_r`. */
    const val EPSILON_R = 5e-3f

    /** Paper alpha-gradient weight `omega`. */
    const val OMEGA = 0.1f

    /** Largest level that receives the small-scale iteration count. */
    const val SMALL_SIZE = 32

    /** Iterations on levels whose longest edge is at most [SMALL_SIZE]. */
    const val SMALL_ITERATIONS = 10

    /** Iterations on every larger pyramid level. */
    const val BIG_ITERATIONS = 2

    /** Below this alpha a pixel is fully transparent and keeps `F = 0`. */
    const val ALPHA_LOW = 0.02f

    /** Above this alpha a pixel is fully opaque and keeps `B = 0`. */
    const val ALPHA_HIGH = 0.98f

    /** Refines only the transition band of a bake band; every other pixel keeps its initial value. */
    const val REFINE_ROUNDS = 2

    /**
     * Builds the low-frequency `F`/`B` estimate for an image at its own resolution.
     *
     * @param image Interleaved RGB in `[0, 1]`.
     * @param alpha Straight alpha in `[0, 1]`.
     * @param width Image width.
     * @param height Image height.
     */
    fun initialize(
        image: FloatArray,
        alpha: FloatArray,
        width: Int,
        height: Int
    ): ForegroundEstimate {
        require(image.size == width * height * ForegroundEstimate.CHANNELS)
        require(alpha.size == width * height)
        val levels = buildPyramid(image, alpha, width, height)
        val coarsest = levels.last()
        var foreground = initialForeground(coarsest.image, coarsest.alpha)
        var background = initialBackground(coarsest.image, coarsest.alpha)
        val coarsestRounds = if (max(coarsest.width, coarsest.height) <= SMALL_SIZE) {
            SMALL_ITERATIONS
        } else {
            BIG_ITERATIONS
        }
        solveLevel(coarsest, foreground, background, coarsestRounds)
        var previousWidth = coarsest.width
        var previousHeight = coarsest.height
        for (levelIndex in levels.size - 2 downTo 0) {
            val level = levels[levelIndex]
            foreground = ImageFilters.resampleRgb(
                foreground, previousWidth, previousHeight, level.width, level.height
            )
            background = ImageFilters.resampleRgb(
                background, previousWidth, previousHeight, level.width, level.height
            )
            solveLevel(level, foreground, background, BIG_ITERATIONS)
            previousWidth = level.width
            previousHeight = level.height
        }
        val full = levels.first()
        return ForegroundEstimate(full.width, full.height, foreground, background)
    }

    /**
     * Seeds a bake band from [initial] and runs [rounds] local rounds inside the transition band.
     *
     * @param initial Low-frequency estimate produced on the proxy; it is bilinearly resampled onto
     *   the band grid before the first round.
     */
    fun refineBand(
        image: FloatArray,
        alpha: FloatArray,
        initial: ForegroundEstimate,
        width: Int,
        height: Int,
        rounds: Int = REFINE_ROUNDS
    ): ForegroundEstimate {
        require(image.size == width * height * ForegroundEstimate.CHANNELS)
        require(alpha.size == width * height)
        val foreground = if (initial.width == width && initial.height == height) {
            initial.foreground.copyOf()
        } else {
            ImageFilters.resampleRgb(initial.foreground, initial.width, initial.height, width, height)
        }
        val background = if (initial.width == width && initial.height == height) {
            initial.background.copyOf()
        } else {
            ImageFilters.resampleRgb(initial.background, initial.width, initial.height, width, height)
        }
        val level = Level(image, alpha, width, height)
        solveLevel(level, foreground, background, rounds.coerceAtLeast(1))
        return ForegroundEstimate(width, height, foreground, background)
    }

    /**
     * Degradation used when the local solver cannot allocate: the image itself is the foreground and
     * the background is left black, so the final composite keeps the original pixels instead of
     * showing a colour cast.
     */
    fun fallback(image: FloatArray, width: Int, height: Int): ForegroundEstimate {
        val foreground = try {
            image.copyOf()
        } catch (oom: OutOfMemoryError) {
            FloatArray(image.size)
        }
        return ForegroundEstimate(width, height, foreground, FloatArray(image.size))
    }

    private fun buildPyramid(
        image: FloatArray,
        alpha: FloatArray,
        width: Int,
        height: Int
    ): List<Level> {
        val levels = mutableListOf(Level(image, alpha, width, height))
        var current = levels.first()
        while (max(current.width, current.height) > SMALL_SIZE && current.width > 4 && current.height > 4) {
            val nextWidth = (current.width / 2).coerceAtLeast(2)
            val nextHeight = (current.height / 2).coerceAtLeast(2)
            val next = Level(
                image = ImageFilters.resampleRgb(
                    current.image, current.width, current.height, nextWidth, nextHeight
                ),
                alpha = ImageFilters.resamplePlane(
                    current.alpha, current.width, current.height, nextWidth, nextHeight
                ),
                width = nextWidth,
                height = nextHeight
            )
            levels.add(next)
            current = next
        }
        return levels
    }

    private fun solveLevel(level: Level, foreground: FloatArray, background: FloatArray, rounds: Int) {
        repeat(rounds) {
            val nextForeground = FloatArray(foreground.size)
            val nextBackground = FloatArray(background.size)
            for (row in 0 until level.height) {
                for (column in 0 until level.width) {
                    val index = row * level.width + column
                    val pixelAlpha = level.alpha[index]
                    if (pixelAlpha <= ALPHA_LOW || pixelAlpha >= ALPHA_HIGH) {
                        copyPixel(foreground, nextForeground, index)
                        copyPixel(background, nextBackground, index)
                        continue
                    }
                    solvePixel(level, foreground, background, index, nextForeground, nextBackground)
                }
            }
            nextForeground.copyInto(foreground)
            nextBackground.copyInto(background)        }
    }

    private fun solvePixel(
        level: Level,
        previousForeground: FloatArray,
        previousBackground: FloatArray,
        index: Int,
        outputForeground: FloatArray,
        outputBackground: FloatArray
    ) {
        val column = index % level.width
        val row = index / level.width
        val pixelAlpha = level.alpha[index]
        if (!hasLocalAlphaVariation(level, column, row)) {
            copyPixel(previousForeground, outputForeground, index)
            copyPixel(previousBackground, outputBackground, index)
            return
        }
        var weightSum = EPSILON_R.toDouble()
        var alphaSum = 0.0
        var alphaSquareSum = 0.0
        val imageSums = DoubleArray(ForegroundEstimate.CHANNELS)
        val alphaImageSums = DoubleArray(ForegroundEstimate.CHANNELS)
        for (offsetRow in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
            for (offsetColumn in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
                val sampleColumn = (column + offsetColumn).coerceIn(0, level.width - 1)
                val sampleRow = (row + offsetRow).coerceIn(0, level.height - 1)
                val sampleIndex = sampleRow * level.width + sampleColumn
                val sampleAlpha = level.alpha[sampleIndex]
                val weight = sampleWeight(pixelAlpha, sampleAlpha).toDouble()
                weightSum += weight
                alphaSum += weight * sampleAlpha
                alphaSquareSum += weight * sampleAlpha * sampleAlpha
                for (channel in 0 until ForegroundEstimate.CHANNELS) {
                    val sample = level.image[sampleIndex * ForegroundEstimate.CHANNELS + channel]
                    imageSums[channel] += weight * sample
                    alphaImageSums[channel] += weight * sampleAlpha * sample
                }
            }
        }
        val determinant = weightSum * alphaSquareSum - alphaSum * alphaSum
        val conditioning = determinant / (weightSum * alphaSquareSum).coerceAtLeast(DETERMINANT_FLOOR)
        // A narrow alpha window makes the local slope underdetermined (the classic matting
        // ill-conditioning). Keeping the upsampled coarse estimate is better than amplifying noise.
        if (conditioning < MIN_CONDITIONING) {
            copyPixel(previousForeground, outputForeground, index)
            copyPixel(previousBackground, outputBackground, index)
            return
        }
        for (channel in 0 until ForegroundEstimate.CHANNELS) {
            val background = (alphaSquareSum * imageSums[channel] -
                alphaSum * alphaImageSums[channel]) / determinant
            val difference = (weightSum * alphaImageSums[channel] -
                alphaSum * imageSums[channel]) / determinant
            val foreground = background + difference
            outputBackground[index * ForegroundEstimate.CHANNELS + channel] =
                background.toFloat().coerceIn(0.0f, 1.0f)
            outputForeground[index * ForegroundEstimate.CHANNELS + channel] =
                foreground.toFloat().coerceIn(0.0f, 1.0f)
        }
    }

    /** Alpha-gradient weighting: similar alphas contribute almost equally, a jump is down-weighted. */
    private fun sampleWeight(pixelAlpha: Float, sampleAlpha: Float): Float {
        val gradient = abs(pixelAlpha - sampleAlpha)
        return exp(-OMEGA * gradient / ALPHA_LOW).coerceIn(MIN_SAMPLE_WEIGHT, 1.0f)
    }

    /**
     * True when the immediate 2x2 neighbourhood carries enough alpha variation to identify the
     * foreground/background line. A flat window keeps the upsampled coarse estimate, which avoids
     * solving an ill-conditioned system on every purely opaque or purely transparent pixel.
     */
    private fun hasLocalAlphaVariation(level: Level, column: Int, row: Int): Boolean {
        val right = (column + 1).coerceAtMost(level.width - 1)
        val bottom = (row + 1).coerceAtMost(level.height - 1)
        val a00 = level.alpha[row * level.width + column]
        val a10 = level.alpha[row * level.width + right]
        val a01 = level.alpha[bottom * level.width + column]
        val a11 = level.alpha[bottom * level.width + right]
        val minimum = minOf(a00, a10, a01, a11)
        val maximum = maxOf(a00, a10, a01, a11)
        return maximum - minimum >= MIN_ALPHA_SPREAD
    }

    private fun initialForeground(image: FloatArray, alpha: FloatArray): FloatArray =
        FloatArray(image.size) { index ->
            if (alpha[index / ForegroundEstimate.CHANNELS] > ALPHA_LOW) image[index] else 0.0f
        }

    private fun initialBackground(image: FloatArray, alpha: FloatArray): FloatArray =
        FloatArray(image.size) { index ->
            if (alpha[index / ForegroundEstimate.CHANNELS] < ALPHA_HIGH) image[index] else 0.0f
        }

    private fun copyPixel(source: FloatArray, target: FloatArray, index: Int) {
        for (channel in 0 until ForegroundEstimate.CHANNELS) {
            target[index * ForegroundEstimate.CHANNELS + channel] =
                source[index * ForegroundEstimate.CHANNELS + channel]
        }
    }

    private data class Level(
        val image: FloatArray,
        val alpha: FloatArray,
        val width: Int,
        val height: Int
    )

    private const val DETERMINANT_FLOOR = 1e-9
    private const val MIN_SAMPLE_WEIGHT = 0.05f
    private const val SAMPLE_RADIUS = 2
    private const val MIN_CONDITIONING = 5e-3
    private const val MIN_ALPHA_SPREAD = 0.01f
}
