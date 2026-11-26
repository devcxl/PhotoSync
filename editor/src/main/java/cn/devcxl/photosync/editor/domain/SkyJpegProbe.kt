package cn.devcxl.photosync.editor.domain

/**
 * Minimal JPEG header probe used by the custom sky store.
 *
 * Only the SOI marker and the first SOF segment are inspected, so listing a library of 4096px skies
 * never decodes a bitmap and importing a payload can reject a non-JPEG before touching disk.
 */
internal object SkyJpegProbe {

    /** True when [payload] starts with a JPEG SOI marker. */
    fun hasSignature(payload: ByteArray): Boolean =
        payload.size >= 3 &&
            payload[0] == 0xFF.toByte() &&
            payload[1] == 0xD8.toByte() &&
            payload[2] == 0xFF.toByte()

    /**
     * Reads the dimensions from the first JPEG SOF segment.
     *
     * The importer already knows the dimensions from the decoded bitmap; this parser only lets the
     * picker show a size for files the store finds on disk.
     */
    fun dimensions(payload: ByteArray): Pair<Int, Int>? {
        if (!hasSignature(payload)) return null
        var offset = 2
        while (offset + 9 < payload.size) {
            if (payload[offset] != 0xFF.toByte()) {
                offset++
                continue
            }
            val marker = payload[offset + 1].toInt() and 0xFF
            if (marker == MARKER_SOI || marker in MARKER_RESTART_RANGE) {
                offset += 2
                continue
            }
            if (marker == MARKER_EOI) return null
            val length = ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                (payload[offset + 3].toInt() and 0xFF)
            if (length < 2 || offset + 2 + length > payload.size) return null
            if (marker in MARKER_SOF_RANGE) {
                val height = ((payload[offset + 5].toInt() and 0xFF) shl 8) or
                    (payload[offset + 6].toInt() and 0xFF)
                val width = ((payload[offset + 7].toInt() and 0xFF) shl 8) or
                    (payload[offset + 8].toInt() and 0xFF)
                return if (width > 0 && height > 0) width to height else null
            }
            offset += 2 + length
        }
        return null
    }

    private const val MARKER_SOI = 0xD8
    private const val MARKER_EOI = 0xD9
    private val MARKER_RESTART_RANGE = 0xD0..0xD7
    private val MARKER_SOF_RANGE = setOf(
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF
    )
}
