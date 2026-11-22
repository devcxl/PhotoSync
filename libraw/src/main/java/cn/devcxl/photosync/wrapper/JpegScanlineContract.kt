package cn.devcxl.photosync.wrapper

/**
 * Pure-Kotlin row accounting for the streaming libjpeg-turbo encoder.
 *
 * Keeping the arithmetic here (instead of inside the JNI encoder) makes the scanline contract
 * testable on the JVM: `jpeg_write_scanlines` accepts any number of full-width rows, but never
 * more than the image has left, and every strip buffer must hold `width * 4` bytes per row.
 */
class JpegScanlineContract(
    val width: Int,
    val height: Int,
    val quality: Int = DEFAULT_QUALITY
) {

    init {
        require(width > 0) { "JPEG width must be positive" }
        require(height > 0) { "JPEG height must be positive" }
        require(quality in MIN_QUALITY..MAX_QUALITY) { "JPEG quality must be within 1..100" }
    }

    /** Bytes of one `JCS_EXT_RGBA` scanline. */
    val rowBytes: Int = width * BYTES_PER_RGBA_PIXEL

    /** Scanlines already handed to the encoder. */
    var rowsWritten: Int = 0
        private set

    /** Scanlines still expected before `jpeg_finish_compress`. */
    val remainingRows: Int
        get() = height - rowsWritten

    /** True once the full image height has been fed. */
    val isComplete: Boolean
        get() = rowsWritten == height

    /**
     * Validates one strip before it is passed to native code.
     *
     * @param rows Number of scanlines that [bufferBytes] claims to hold.
     * @param bufferBytes Byte capacity of the direct buffer backing the strip.
     * @throws IllegalArgumentException when the strip exceeds the remaining height or the buffer
     *   is too small for `rows` full-width scanlines.
     */
    fun requireStrip(rows: Int, bufferBytes: Int) {
        require(rows in 1..remainingRows) {
            "Strip of $rows rows exceeds the $remainingRows remaining scanlines"
        }
        require(bufferBytes >= rows.toLong() * rowBytes) {
            "Strip buffer of $bufferBytes bytes cannot hold $rows rows of $rowBytes bytes"
        }
    }

    /**
     * Advances the write cursor after the native encoder consumed [rows] scanlines.
     *
     * @return The number of scanlines actually accepted (clamped to [remainingRows]).
     */
    fun acceptRows(rows: Int): Int {
        require(rows >= 0) { "rows must not be negative" }
        val accepted = minOf(rows, remainingRows)
        rowsWritten += accepted
        return accepted
    }

    companion object {

        /** RGBA texels: the encoder consumes `JCS_EXT_RGBA` with four bytes per pixel. */
        const val BYTES_PER_RGBA_PIXEL = 4

        /** Baseline single-scan quality used by the bake exporter. */
        const val DEFAULT_QUALITY = 95

        /** Encoder rows per MCU block; strips are aligned to it so no extra padding rows are fed. */
        const val MCU_ROWS = 16

        private const val MIN_QUALITY = 1
        private const val MAX_QUALITY = 100

        /**
         * Aligns [rows] down to a whole number of MCU rows, keeping a short final strip intact.
         */
        fun alignedStripRows(rows: Int): Int {
            if (rows < MCU_ROWS) return rows
            return rows - rows % MCU_ROWS
        }
    }
}
