package cn.devcxl.photosync.editor.cloud

import java.security.MessageDigest

import kotlin.math.abs

import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.GeometryOps

/**
 * Deterministic signature of every Pass A geometry input a `CloudEditLayer` is bound to.
 *
 * A cloud result is generated for exactly one framing: crop, rotation, flips, straighten,
 * perspective quad and liquify mesh. Pass A maps that framing onto the canvas, so reusing the layer
 * after any of those inputs changed would paste a result generated for another composition. The
 * digest therefore covers all of them, and a mismatch makes the renderer skip the layer instead of
 * blending pixels that no longer line up.
 *
 * Floating point values are canonicalised by their IEEE-754 bit pattern. Values within
 * [EPSILON] of zero collapse to `+0.0`, so `-0.0` and `0.0` cannot produce two digests for the same
 * framing, while a genuine one-bit slider change still changes the digest.
 */
object CloudGeometryDigest {

    /** Prefix of the canonical form; bumping it invalidates every previously stored digest. */
    private const val DIGEST_VERSION = "cloud_geometry_v1"

    private const val SHA_256 = "SHA-256"

    /** Values closer to zero than this are treated as neutral. */
    private const val EPSILON = 1e-6f

    private const val HEX_RADIX = 16

    /**
     * Returns the lowercase SHA-256 hex digest of [geometry].
     *
     * The function is pure and stable across processes and locales.
     */
    fun digest(geometry: GeometryOps): String {
        val hash = MessageDigest.getInstance(SHA_256)
            .digest(canonicalForm(geometry).toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(hash.size * 2)
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return hex.toString()
    }

    /** True when [layer] was generated for exactly [geometry]. A missing layer never matches. */
    fun matches(layer: CloudEditLayerRef?, geometry: GeometryOps): Boolean {
        val bound = layer?.geometryDigest?.trim().orEmpty()
        if (bound.isEmpty()) return false
        return bound.equals(digest(geometry), ignoreCase = true)
    }

    /**
     * True when a cloud layer exists but belongs to a different Pass A framing.
     *
     * This is the guard condition for the non-destructive geometry confirmation sheet.
     */
    fun isMismatch(layer: CloudEditLayerRef?, geometry: GeometryOps): Boolean =
        layer != null && !matches(layer, geometry)

    /** Canonical, human-inspectable form the digest is computed over. */
    internal fun canonicalForm(geometry: GeometryOps): String = buildString {
        append(DIGEST_VERSION)
        append("|rotate90=").append(geometry.rotate90)
        append("|flipH=").append(if (geometry.flipH) '1' else '0')
        append("|flipV=").append(if (geometry.flipV) '1' else '0')
        append("|straighten=").append(floatBits(geometry.straighten))
        append("|crop=")
        append(floatBits(geometry.crop.left)).append(',')
        append(floatBits(geometry.crop.top)).append(',')
        append(floatBits(geometry.crop.right)).append(',')
        append(floatBits(geometry.crop.bottom))
        append("|perspective=")
        geometry.perspective.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(floatBits(value))
        }
        val liquify = geometry.liquify
        append("|liquify=").append(liquify?.sha256?.trim()?.lowercase().orEmpty())
        append("|liquifyGrid=").append(liquify?.gridSize ?: 0)
    }

    private fun floatBits(value: Float): String {
        val normalized = if (value.isFinite() && abs(value) < EPSILON) 0.0f else value
        return Integer.toString(java.lang.Float.floatToIntBits(normalized), HEX_RADIX)
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
