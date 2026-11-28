package cn.devcxl.photosync.editor.cloud

import kotlin.math.roundToInt

import android.graphics.Bitmap
import android.graphics.Color

import cn.devcxl.photosync.editor.vision.SubjectMask
import cn.devcxl.photosync.editor.vision.SubjectMaskCodec

/**
 * CPU mask preparation shared by the cloud request builder and the persisted blend mask.
 *
 * Two different masks leave the device for one local edit:
 * - the **provider mask**, whose transparent pixels are the editable region (see
 *   [CloudMaskPngEncoder]);
 * - the **blend mask**, whose value is the coverage of the cloud result over the original frame,
 *   persisted next to the layer and consumed by
 *   [cn.devcxl.photosync.editor.render.CloudLayerBlender].
 *
 * Coverage is stored as an 8-bit grayscale PNG so it is inspectable, compact and independent of the
 * provider contract. Blurring is a separable box blur applied twice; it approximates a Gaussian of
 * roughly [featherPx] pixels and keeps the mask cheap enough for a 2048 px upload.
 */
internal object CloudBlendMask {

    private const val PASSES = 2

    /** Feather radii above this are clamped; the layer schema already caps them at 128 px. */
    private const val MAX_RADIUS = 64

    /**
     * Coverage of a painted stroke overlay: white, opaque strokes become `1`, untouched pixels `0`.
     *
     * @return A new row-major coverage array, or `null` when the bitmap is unusable.
     */
    fun paintedCoverage(mask: Bitmap, width: Int, height: Int): FloatArray? {
        if (mask.isRecycled || width <= 0 || height <= 0) return null
        val scaled = if (mask.width == width && mask.height == height) {
            mask
        } else {
            Bitmap.createScaledBitmap(mask, width, height, true)
        }
        return try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            FloatArray(pixels.size) { index ->
                val color = pixels[index]
                val luminance = (
                    Color.red(color) * LUMINANCE_RED +
                        Color.green(color) * LUMINANCE_GREEN +
                        Color.blue(color) * LUMINANCE_BLUE
                    ) / LUMINANCE_SCALE
                luminance * Color.alpha(color) / BYTE_MAX
            }
        } finally {
            if (scaled !== mask) scaled.recycle()
        }
    }

    /**
     * Coverage of the inverted [subject]: the background (`alpha = 0`) is editable, the subject is
     * preserved.
     */
    fun backgroundCoverage(subject: SubjectMask, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0) { "Mask dimensions must be positive" }
        val resampled = subject.resampled(width, height)
        return FloatArray(width * height) { index ->
            (1.0f - resampled.alpha[index]).coerceIn(0.0f, 1.0f)
        }
    }

    /** Applies [featherPx] of edge softening; a non-positive radius returns [coverage] unchanged. */
    fun feather(coverage: FloatArray, width: Int, height: Int, featherPx: Float): FloatArray {
        require(width > 0 && height > 0) { "Mask dimensions must be positive" }
        require(coverage.size == width * height) { "Mask buffer does not match its dimensions" }
        if (!featherPx.isFinite() || featherPx <= 0.0f) return coverage
        val radius = (featherPx / 2.0f).roundToInt().coerceIn(1, MAX_RADIUS)
        val scratch = FloatArray(coverage.size)
        var current = coverage.copyOf()
        repeat(PASSES) {
            blurHorizontal(current, scratch, width, height, radius)
            blurVertical(scratch, current, width, height, radius)
        }
        return current
    }

    /**
     * Encodes [coverage] as the 8-bit grayscale PNG persisted under
     * [cn.devcxl.photosync.editor.domain.AiAssetKind.CLOUD_MASK].
     *
     * @return The PNG bytes, or `null` when the coverage cannot be encoded.
     */
    fun encode(coverage: FloatArray, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0 || coverage.size != width * height) return null
        return SubjectMaskCodec.encode(SubjectMask(width, height, coverage))
    }

    private fun blurHorizontal(
        source: FloatArray,
        target: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val window = 2 * radius + 1
        for (row in 0 until height) {
            val offset = row * width
            var sum = 0.0f
            for (step in -radius..radius) {
                sum += source[offset + step.coerceIn(0, width - 1)]
            }
            for (column in 0 until width) {
                target[offset + column] = sum / window
                val leaving = (column - radius).coerceIn(0, width - 1)
                val entering = (column + radius + 1).coerceIn(0, width - 1)
                sum += source[offset + entering] - source[offset + leaving]
            }
        }
    }

    private fun blurVertical(
        source: FloatArray,
        target: FloatArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val window = 2 * radius + 1
        for (column in 0 until width) {
            var sum = 0.0f
            for (step in -radius..radius) {
                sum += source[step.coerceIn(0, height - 1) * width + column]
            }
            for (row in 0 until height) {
                target[row * width + column] = sum / window
                val leaving = (row - radius).coerceIn(0, height - 1)
                val entering = (row + radius + 1).coerceIn(0, height - 1)
                sum += source[entering * width + column] - source[leaving * width + column]
            }
        }
    }

    private const val BYTE_MAX = 255.0f
    private const val LUMINANCE_RED = 299.0f
    private const val LUMINANCE_GREEN = 587.0f
    private const val LUMINANCE_BLUE = 114.0f
    private const val LUMINANCE_SCALE = 1000.0f
}
