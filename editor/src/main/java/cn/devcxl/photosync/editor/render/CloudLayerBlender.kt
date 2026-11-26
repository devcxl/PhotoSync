package cn.devcxl.photosync.editor.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import timber.log.Timber

import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.GeometryOps

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

    /**
     * Merges already prepared [pixels] into a packed `ARGB_8888` buffer in place.
     *
     * Use this overload when the caller decoded the layer once for several consumers; [blendArgb]
     * decodes and verifies the layer itself.
     */
    fun blendPrepared(
        target: IntArray,
        width: Int,
        height: Int,
        pixels: CloudLayerPixels
    ): CloudBlendResult {
        require(width > 0 && height > 0) { "Frame dimensions must be positive" }
        require(target.size == width * height) { "Frame buffer does not match its size" }
        require(pixels.width == width && pixels.height == height) {
            "Prepared layer does not match the frame"
        }
        var blended = 0L
        for (index in target.indices) {
            val factor = coverageAt(pixels, index)
            if (factor <= 0.0f) continue
            target[index] = blendPixel(target[index], pixels.argb[index], factor)
            blended++
        }
        return CloudBlendResult(CloudBlendStatus.APPLIED, blended)
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
        const val BYTE_MAX = 255.0f
        const val BYTE_MAX_INT = 255
        const val PERCENT_MAX = 100.0f
        const val SHA256_HEX_LENGTH = 64
        const val MAX_DECODE_PIXELS = 16L * 1024L * 1024L
    }
}
