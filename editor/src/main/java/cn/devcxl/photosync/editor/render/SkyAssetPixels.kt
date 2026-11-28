package cn.devcxl.photosync.editor.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory

import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.domain.SkyAssetStore
import cn.devcxl.photosync.editor.domain.SkyOps

/** Packed ARGB pixels of the frame-aspect-cropped replacement sky. */
internal data class SkyAssetPixels(
    val argb: IntArray,
    val width: Int,
    val height: Int
) {

    init {
        require(argb.size == width * height) { "Sky asset pixels must match its dimensions" }
    }
}

/**
 * Reads and decodes the custom/pack replacement image of a `SkyOps`.
 *
 * Decoding is bounded to the path's budget so a 4096px user sky never inflates the bake texture
 * beyond what the Pass D sampler needs.
 */
internal object SkyAssetDecoder {

    fun load(assets: SkyAssetStore, ops: SkyOps, maxEdge: Int): SkyAssetPixels? {
        val payload = assets.read(ops) ?: return null
        val bitmap = decodeBounded(payload, maxEdge) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            SkyAssetPixels(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Centre-crops [asset] to the target frame [aspect], or returns it unchanged when it fits. */
    fun cropToAspect(asset: SkyAssetPixels, aspect: Float): SkyAssetPixels {
        if (!aspect.isFinite() || aspect <= 0.0f) return asset
        val sourceAspect = asset.width.toFloat() / asset.height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        val offsetX: Int
        val offsetY: Int
        if (sourceAspect > aspect) {
            targetHeight = asset.height
            targetWidth = (asset.height * aspect).roundToInt().coerceIn(1, asset.width)
            offsetX = (asset.width - targetWidth) / 2
            offsetY = 0
        } else {
            targetWidth = asset.width
            targetHeight = (asset.width / aspect).roundToInt().coerceIn(1, asset.height)
            offsetX = 0
            offsetY = (asset.height - targetHeight) / 2
        }
        if (targetWidth == asset.width && targetHeight == asset.height) return asset
        val out = IntArray(targetWidth * targetHeight)
        for (row in 0 until targetHeight) {
            val sourceRow = row + offsetY
            for (column in 0 until targetWidth) {
                out[row * targetWidth + column] =
                    asset.argb[sourceRow * asset.width + column + offsetX]
            }
        }
        return SkyAssetPixels(out, targetWidth, targetHeight)
    }

    private fun decodeBounded(payload: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return BitmapFactory.decodeByteArray(payload, 0, payload.size, options)
    }

    private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxEdge) sample *= 2
        return sample
    }
}
