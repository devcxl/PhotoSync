package cn.devcxl.photosync.editor.vision

import java.io.ByteArrayOutputStream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap

/**
 * Encodes the two lossless payloads of one inpainting patch.
 *
 * Implemented as a seam so [MiGanInpainter] can be tested without Android graphics: the production
 * codec is [BitmapInpaintPatchCodec], the JVM tests inject a deterministic fake.
 */
interface InpaintPatchEncoder {

    /** Encodes opaque `ARGB_8888` pixels as a lossless RGB PNG, or `null` when encoding fails. */
    fun encodeRgb(argb: IntArray, width: Int, height: Int): ByteArray?

    /**
     * Encodes the feathered alpha mask as a PNG whose **red channel** carries the coverage.
     *
     * Storing the mask in RGB instead of the alpha channel keeps it readable by any decoder and
     * avoids Android's premultiplied-alpha rounding.
     */
    fun encodeAlpha(alpha: ByteArray, width: Int, height: Int): ByteArray?
}

/** One decoded patch: opaque RGB pixels plus the 8-bit feathered coverage. */
class InpaintDecodedPatch(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val alpha: ByteArray
) {

    init {
        require(argb.size == width * height) { "Decoded patch payload does not match its size" }
        require(alpha.size == width * height) { "Decoded patch alpha does not match its size" }
    }
}

/**
 * Decodes the patch/mask PNG pair stored by [cn.devcxl.photosync.editor.domain.AiAssetStore].
 *
 * A decoder returns `null` for a corrupt payload instead of throwing, so the Pre-Pass A compositor
 * can skip one damaged patch and keep the rest of the edit renderable.
 */
fun interface InpaintPatchDecoder {

    /** Returns the decoded pair, or `null` when either payload is missing or malformed. */
    fun decode(patchPng: ByteArray, maskPng: ByteArray): InpaintDecodedPatch?
}

/**
 * Android PNG codec used by the production inpainter and the Pre-Pass A compositor.
 *
 * Both directions use `BitmapFactory`/`Bitmap.compress` with `ARGB_8888`; no metadata, text chunk or
 * colour profile is written, and the patch is never re-encoded as JPEG because it is a lossless
 * local repair that the bake path later reuses byte for byte.
 */
object BitmapInpaintPatchCodec : InpaintPatchEncoder, InpaintPatchDecoder {

    override fun encodeRgb(argb: IntArray, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0 || argb.size != width * height) return null
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(argb, 0, width, 0, 0, width, height)
            compress(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun encodeAlpha(alpha: ByteArray, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0 || alpha.size != width * height) return null
        val argb = IntArray(alpha.size) { index ->
            val value = alpha[index].toInt() and 0xFF
            (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        return encodeRgb(argb, width, height)
    }

    override fun decode(patchPng: ByteArray, maskPng: ByteArray): InpaintDecodedPatch? {
        val patch = decodeBitmap(patchPng) ?: return null
        val mask = try {
            decodeBitmap(maskPng)
        } catch (error: RuntimeException) {
            null
        }
        if (mask == null) {
            patch.recycle()
            return null
        }
        if (patch.width != mask.width || patch.height != mask.height) {
            patch.recycle()
            mask.recycle()
            return null
        }
        val width = patch.width
        val height = patch.height
        return try {
            val argb = IntArray(width * height)
            val maskPixels = IntArray(width * height)
            patch.getPixels(argb, 0, width, 0, 0, width, height)
            mask.getPixels(maskPixels, 0, width, 0, 0, width, height)
            val alpha = ByteArray(width * height) { index ->
                ((maskPixels[index] shr 16) and 0xFF).toByte()
            }
            InpaintDecodedPatch(width = width, height = height, argb = argb, alpha = alpha)
        } finally {
            patch.recycle()
            mask.recycle()
        }
    }

    private fun decodeBitmap(payload: ByteArray): Bitmap? {
        if (payload.isEmpty()) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(payload, 0, payload.size, options)
    }

    private fun compress(bitmap: Bitmap): ByteArray? {
        val output = ByteArrayOutputStream()
        return try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                null
            } else {
                output.toByteArray()
            }
        } catch (error: RuntimeException) {
            null
        }
    }
}
