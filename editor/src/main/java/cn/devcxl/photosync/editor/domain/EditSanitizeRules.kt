package cn.devcxl.photosync.editor.domain

/**
 * Shared validation primitives of [EditValidator] and the content-addressed stores.
 *
 * Kept in one place so a digest, path or numeric bound is checked identically wherever a graph or
 * an external payload crosses a trust boundary; a divergence here would let one caller accept a
 * reference another caller rejects.
 */

/** Length of a full SHA-256 digest in hex characters. */
internal const val SHA256_HEX_LENGTH = 64

/** Longest accepted relative asset path. */
internal const val MAX_RELATIVE_PATH_LENGTH = 512

/** Longest accepted sky-asset id or relative path. */
internal const val MAX_SKY_ASSET_REF_LENGTH = 512

/** True when [value] is exactly one lower- or upper-case hex SHA-256 digest. */
internal fun isSha256Hex(value: String): Boolean =
    value.length == SHA256_HEX_LENGTH &&
        value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

/**
 * Keeps a reference only when it stays inside the app's private storage: blank, absolute or
 * parent-escaping paths degrade to `null` instead of pointing a loader at arbitrary files.
 */
internal fun sanitizeRelativePath(raw: String): String? {
    val path = raw.trim()
    val confined = path.isNotEmpty() &&
        path.length <= MAX_RELATIVE_PATH_LENGTH &&
        !path.startsWith("/") &&
        !path.startsWith("\\") &&
        !path.contains("..") &&
        !path.contains('\\')
    return if (confined) path else null
}

/** Sky assets may be procedural ids as well as relative paths, but never escape storage. */
internal fun sanitizeAssetIdOrPath(raw: String): String {
    val reference = raw.trim()
    val confined = reference.length <= MAX_SKY_ASSET_REF_LENGTH &&
        !reference.contains("..") &&
        !reference.startsWith("/") &&
        !reference.startsWith("\\") &&
        !reference.contains('\\')
    return if (confined) reference else ""
}

/** Empty means "pending"; anything else must be a full SHA-256 hex digest. */
internal fun sanitizeSha256(raw: String): String {
    val digest = raw.trim()
    return if (digest.isEmpty() || isSha256Hex(digest)) digest else ""
}

/** Clamps every band of [this] shift into its legal `-100..100` range. */
internal fun HslBandShift.sanitized(): HslBandShift = HslBandShift(
    hue = hue.clampSigned100(),
    saturation = saturation.clampSigned100(),
    luminance = luminance.clampSigned100()
)

/** Clamps [this] into `-100..100`. */
internal fun Int.clampSigned100(): Int = coerceIn(-100, 100)

/** Clamps [this] into `0..100`. */
internal fun Int.clampUnsigned100(): Int = coerceIn(0, 100)

/** Clamps [value] into [min]..[max], replacing a `NaN` or infinity with [defaultValue]. */
internal fun sanitizeFloat(value: Float, defaultValue: Float, min: Float, max: Float): Float {
    return if (value.isFinite()) value.coerceIn(min, max) else defaultValue
}
