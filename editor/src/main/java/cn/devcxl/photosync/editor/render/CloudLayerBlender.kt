package cn.devcxl.photosync.editor.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import timber.log.Timber

import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.GeometryOps

/** Why a [CloudEditLayerRef] was or was not blended into a frame. */
enum class CloudBlendStatus {

    /** Pixels were fused with the requested opacity and optional feathered mask. */
    APPLIED,

    /** The graph has no cloud layer. */
    NO_LAYER,

    /** The layer belongs to a different Pass A framing and must not be pasted. */
    GEOMETRY_MISMATCH,

    /** The layer exists but its opacity is zero, so the frame is already correct. */
    OPACITY_ZERO,

    /** The layer or its mask file is missing, truncated or fails its SHA-256 check. */
    ASSET_INVALID
}

/**
 * Result of one cloud blend.
 *
 * @property status What happened.
 * @property blendedPixels Pixels whose coverage was greater than zero.
 */
data class CloudBlendResult(
    val status: CloudBlendStatus,
    val blendedPixels: Long = 0L
) {
    /** True when the frame now contains the cloud layer. */
    val isApplied: Boolean
        get() = status == CloudBlendStatus.APPLIED

    /** True when the geometry guard blocked the blend. */
    val isGeometryMismatch: Boolean
        get() = status == CloudBlendStatus.GEOMETRY_MISMATCH
}

/**
 * Decoded, frame-sized pixels of one cloud layer, ready for CPU blending or a GL upload.
 *
 * @property width Frame width the pixels were resampled to.
 * @property height Frame height the pixels were resampled to.
 * @property argb Straight `ARGB_8888` cloud pixels in row-major order.
 * @property coverage Optional blend coverage (`0..1`, row-major); `null` means the whole frame.
 * @property opacity Effective opacity in `0..1` after the `0..100` graph value was normalised.
 */
class CloudLayerPixels(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val coverage: FloatArray?,
    val opacity: Float
)

/** Outcome of [CloudLayerBlender.prepare]. */
sealed interface CloudLayerLoad {

    /**
     * Decoded pixels ready for blending or texture staging.
     *
     * @property pixels Frame-sized cloud pixels and their coverage.
     */
    class Ready(val pixels: CloudLayerPixels) : CloudLayerLoad

    /**
     * Nothing to apply; [status] explains why.
     *
     * @property status Why the layer was skipped.
     */
    class NotApplied(val status: CloudBlendStatus) : CloudLayerLoad
}

/**
 * CPU fusion of a `CloudEditLayer` into the Post-Pass A / Pre-Pass B frame.
 *
 * ## Placement
 * The layer is mounted **after** Pass A and **before** Pass B. Blending here means every later
 * stage — linear tone, display colour, 3D LUT, grain, vignette — grades the cloud result together
 * with the camera frame, instead of the cloud image bypassing local colour work.
 *
 * ## Guards
 * Each call verifies the layer digest through [AiAssetStore] and the `geometryDigest` binding
 * through [CloudGeometryDigest]. A mismatch returns [CloudBlendStatus.GEOMETRY_MISMATCH] and leaves
 * the destination untouched, so a layer generated for one crop can never be pasted onto another.
 *
 * ## Formats
 * - [blendBitmap] and [blendArgb] work on display-referred `ARGB_8888` pixels.
 * - [blendRgbaFloat] works on straight RGBA floats in the same encoding as the destination, which
 *   the 16-bit preview paths use.
 *
 * All methods read files and decode bitmaps; call them off the main thread.
 */
class CloudLayerBlender(private val assetStore: AiAssetStore) {

    /** Merges the layer into [base] in place. */
    fun blendBitmap(
        base: Bitmap,
        layer: CloudEditLayerRef?,
        geometry: GeometryOps
    ): CloudBlendResult {
        if (base.isRecycled || base.width <= 0 || base.height <= 0) {
            return CloudBlendResult(CloudBlendStatus.ASSET_INVALID)
        }
        val pixels = IntArray(base.width * base.height)
        base.getPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        val result = blendArgb(pixels, base.width, base.height, layer, geometry)
        if (result.isApplied) {
            base.setPixels(pixels, 0, base.width, 0, 0, base.width, base.height)
        }
        return result
    }

    /** Merges the layer into a packed `ARGB_8888` buffer in place. */
    fun blendArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        layer: CloudEditLayerRef?,
        geometry: GeometryOps
    ): CloudBlendResult {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(pixels.size == width * height) { "Frame buffer does not match its size" }
        val loaded = prepare(layer, geometry, width, height)
        if (loaded is CloudLayerLoad.NotApplied) return CloudBlendResult(loaded.status)
        val cloud = (loaded as CloudLayerLoad.Ready).pixels
        var blended = 0L
        for (index in pixels.indices) {
            val factor = coverageAt(cloud, index)
            if (factor <= 0.0f) continue
            pixels[index] = blendPixel(pixels[index], cloud.argb[index], factor)
            blended++
        }
        return CloudBlendResult(CloudBlendStatus.APPLIED, blended)
    }

    /**
     * Merges the layer into a straight `RGBA` float buffer in place.
     *
     * @param pixels `width * height * 4` floats. Only RGB is blended; alpha is left to the frame.
     */
    fun blendRgbaFloat(
        pixels: FloatArray,
        width: Int,
        height: Int,
        layer: CloudEditLayerRef?,
        geometry: GeometryOps
    ): CloudBlendResult {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(pixels.size == width * height * RGBA_CHANNELS) {
            "Frame buffer does not match its size"
        }
        val loaded = prepare(layer, geometry, width, height)
        if (loaded is CloudLayerLoad.NotApplied) return CloudBlendResult(loaded.status)
        val cloud = (loaded as CloudLayerLoad.Ready).pixels
        var blended = 0L
        for (index in 0 until width * height) {
            val factor = coverageAt(cloud, index)
            if (factor <= 0.0f) continue
            val target = index * RGBA_CHANNELS
            val source = cloud.argb[index]
            val inverse = 1.0f - factor
            pixels[target] = pixels[target] * inverse + Color.red(source) / BYTE_MAX * factor
            pixels[target + 1] =
                pixels[target + 1] * inverse + Color.green(source) / BYTE_MAX * factor
            pixels[target + 2] =
                pixels[target + 2] * inverse + Color.blue(source) / BYTE_MAX * factor
            blended++
        }
        return CloudBlendResult(CloudBlendStatus.APPLIED, blended)
    }

    /**
     * Decodes and resamples the layer for [width] x [height] without touching a destination frame.
     *
     * This is the texture-preparation seam: [CloudLayerTextures.stage] consumes the result.
     */
    fun prepare(
        layer: CloudEditLayerRef?,
        geometry: GeometryOps,
        width: Int,
        height: Int
    ): CloudLayerLoad {
        if (layer == null) return CloudLayerLoad.NotApplied(CloudBlendStatus.NO_LAYER)
        if (width <= 0 || height <= 0) {
            return CloudLayerLoad.NotApplied(CloudBlendStatus.ASSET_INVALID)
        }
        if (!CloudGeometryDigest.matches(layer, geometry)) {
            Timber.w("Cloud layer skipped: geometry digest mismatch")
            return CloudLayerLoad.NotApplied(CloudBlendStatus.GEOMETRY_MISMATCH)
        }
        val opacity = layer.opacity.coerceIn(0, 100) / PERCENT_MAX
        if (opacity <= 0.0f) return CloudLayerLoad.NotApplied(CloudBlendStatus.OPACITY_ZERO)
        val imageDigest = layer.sha256.trim().takeIf(::isSha256Hex)
            ?: digestFromPath(layer.imageRelPath)
            ?: return invalid()
        val bytes = assetStore.read(layer.imageRelPath, imageDigest) ?: return invalid()
        val bitmap = decode(bytes) ?: return invalid()
        try {
            val argb = resampleArgb(bitmap, width, height) ?: return invalid()
            val coverage = readCoverage(layer, width, height)
            if (layer.maskRelPath != null && coverage == null) return invalid()
            return CloudLayerLoad.Ready(CloudLayerPixels(width, height, argb, coverage, opacity))
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun readCoverage(layer: CloudEditLayerRef, width: Int, height: Int): FloatArray? {
        val path = layer.maskRelPath ?: return null
        val digest = digestFromPath(path) ?: return null
        val bytes = assetStore.read(path, digest) ?: return null
        val bitmap = decode(bytes) ?: return null
        try {
            val scaled = if (bitmap.width == width && bitmap.height == height) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            }
            try {
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                // Blend masks are 8-bit grayscale PNGs; every channel carries the same coverage.
                return FloatArray(pixels.size) { index -> Color.red(pixels[index]) / BYTE_MAX }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun resampleArgb(bitmap: Bitmap, width: Int, height: Int): IntArray? {
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            pixels
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_DECODE_PIXELS) {
            Timber.w("Cloud layer rejected: decoded extent exceeds the preview budget")
            return null
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun coverageAt(pixels: CloudLayerPixels, index: Int): Float {
        val mask = pixels.coverage?.getOrNull(index) ?: 1.0f
        val layerAlpha = Color.alpha(pixels.argb[index]) / BYTE_MAX
        return (pixels.opacity * mask.coerceIn(0.0f, 1.0f) * layerAlpha).coerceIn(0.0f, 1.0f)
    }

    private fun blendPixel(base: Int, over: Int, alpha: Float): Int {
        val inverse = 1.0f - alpha
        val red = (Color.red(over) * alpha + Color.red(base) * inverse + 0.5f).toInt()
        val green = (Color.green(over) * alpha + Color.green(base) * inverse + 0.5f).toInt()
        val blue = (Color.blue(over) * alpha + Color.blue(base) * inverse + 0.5f).toInt()
        return Color.argb(
            Color.alpha(base),
            red.coerceIn(0, BYTE_MAX_INT),
            green.coerceIn(0, BYTE_MAX_INT),
            blue.coerceIn(0, BYTE_MAX_INT)
        )
    }

    private fun invalid(): CloudLayerLoad.NotApplied {
        Timber.w("Cloud layer skipped: asset failed verification")
        return CloudLayerLoad.NotApplied(CloudBlendStatus.ASSET_INVALID)
    }

    private fun digestFromPath(relativePath: String): String? {
        val name = relativePath.substringAfterLast('/').substringBeforeLast('.')
        return name.substringAfterLast('_', "").takeIf(::isSha256Hex)
    }

    private fun isSha256Hex(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private companion object {
        const val RGBA_CHANNELS = 4
        const val BYTE_MAX = 255.0f
        const val BYTE_MAX_INT = 255
        const val PERCENT_MAX = 100.0f
        const val SHA256_HEX_LENGTH = 64
        const val MAX_DECODE_PIXELS = 16L * 1024L * 1024L
    }
}
