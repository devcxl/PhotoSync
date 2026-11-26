package cn.devcxl.photosync.editor.vision

/**
 * Lossless 8-bit grayscale PNG codec for [SkyMask] payloads.
 *
 * Sky masks share the PNG layout of [SubjectMaskCodec] — 8-bit grayscale, filter type 0 on write and
 * the full filter set on read — so the content-addressed store keeps inspectable `.png` files and a
 * future external mask import needs no second decoder.
 */
internal object SkyMaskCodec {

    private val NAME_REGEX = Regex("sky_mask_([0-9a-fA-F]{64})\\.png")

    /** Encodes [mask] as an 8-bit grayscale PNG. */
    fun encode(mask: SkyMask): ByteArray? =
        SubjectMaskCodec.encode(SubjectMask(mask.width, mask.height, mask.probability))

    /**
     * Decodes a PNG written by [encode].
     *
     * @return The mask with its recomputed coverage ratio, or `null` for an unsupported payload.
     */
    fun decode(payload: ByteArray): SkyMask? {
        val decoded = SubjectMaskCodec.decode(payload) ?: return null
        return SkyMask(
            width = decoded.width,
            height = decoded.height,
            probability = decoded.alpha,
            coverageRatio = SkyMask.coverageOf(decoded.alpha)
        )
    }

    /**
     * Extracts the SHA-256 digest embedded in a content-addressed `sky_mask_<sha>.png` path.
     *
     * @return The 64-character digest, or `null` when [maskRef] is not a sky mask path.
     */
    fun digestFromRef(maskRef: String): String? {
        val name = maskRef.substringAfterLast('/', "")
        return NAME_REGEX.matchEntire(name)?.groupValues?.get(1)
    }
}
