package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import timber.log.Timber

/**
 * Owns the two `RGBA8` textures of one sky layer: the source-space probability mask and the
 * frame-aspect-cropped replacement image.
 *
 * The mask is uploaded as `R = probability`; Pass B and Pass D sample its red channel. Uploading the
 * source-space mask (instead of a band-aligned plane) keeps every preview and every bake band on the
 * same texture: the shader maps the current output UV through the Pass A inverse homography, the
 * optional `uWarpTex` displacement and the band's tile transform.
 *
 * A staged layer is uploaded on the GL thread; a failed upload unbinds both textures, which closes
 * the shader's sky branches and renders the original sky rather than a half-decoded replacement.
 */
internal class SkyLayerTextures {

    /** Mask texture name, or `0` when no mask is uploaded. */
    var maskTextureId: Int = 0
        private set

    /** Replacement image texture name, or `0` for a procedural sky or a failed upload. */
    var assetTextureId: Int = 0
        private set

    /** True when the mask can be sampled by the next draw. */
    val isActive: Boolean
        get() = maskTextureId != 0

    /** True when a replacement image is bound. */
    val hasAsset: Boolean
        get() = assetTextureId != 0

    @Volatile
    private var staged: SkyLayerPixels? = null

    @Volatile
    private var needsUpload: Boolean = false

    private var maskBuffer: ByteBuffer? = null
    private var assetBuffer: ByteBuffer? = null

    /** Stages [layer] for the next GL-thread upload, or `null` to unbind the sky. */
    fun stage(layer: SkyLayerPixels?) {
        staged = layer
        needsUpload = true
    }

    /** Uploads the staged layer if it changed. Must run on the GL thread. */
    fun uploadIfNeeded() {
        if (!needsUpload) return
        needsUpload = false
        val layer = staged
        if (layer == null || !layer.isActive) {
            clearBindings()
            return
        }
        val maskUploaded = uploadMask(layer.mask)
        val assetUploaded = layer.asset?.let(::uploadAsset) ?: false
        if (!maskUploaded) {
            Timber.w("The sky mask texture could not be uploaded; rendering the original sky")
            clearBindings()
            return
        }
        if (layer.asset != null && !assetUploaded) {
            Timber.w("The replacement sky texture could not be uploaded; rendering the original sky")
            clearBindings()
        }
    }

    /** Forgets both GL names after the EGL context was recreated. */
    fun invalidate() {
        maskTextureId = 0
        assetTextureId = 0
        needsUpload = staged != null
    }

    /** Deletes both textures; safe only while a context is current. */
    fun release() {
        val ids = intArrayOf(maskTextureId, assetTextureId).filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        maskTextureId = 0
        assetTextureId = 0
        maskBuffer = null
        assetBuffer = null
    }

    private fun clearBindings() {
        maskTextureId = 0
        assetTextureId = 0
    }

    private fun uploadMask(mask: cn.devcxl.photosync.editor.vision.SkyMask): Boolean {
        val required = mask.width * mask.height * BYTES_PER_TEXEL
        val bytes = maskBuffer?.takeIf { it.capacity() >= required }
            ?: ByteBuffer.allocateDirect(required)
                .order(ByteOrder.nativeOrder())
                .also { maskBuffer = it }
        bytes.rewind()
        for (value in mask.probability) {
            val level = (value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            bytes.put(level.toByte())
            bytes.put(level.toByte())
            bytes.put(level.toByte())
            bytes.put(255.toByte())
        }
        bytes.rewind()
        maskTextureId = uploadTexture(maskTextureId, mask.width, mask.height, bytes, GLES20.GL_LINEAR)
        return maskTextureId != 0
    }

    private fun uploadAsset(asset: SkyAssetPixels): Boolean {
        val required = asset.width * asset.height * BYTES_PER_TEXEL
        val bytes = assetBuffer?.takeIf { it.capacity() >= required }
            ?: ByteBuffer.allocateDirect(required)
                .order(ByteOrder.nativeOrder())
                .also { assetBuffer = it }
        bytes.rewind()
        for (pixel in asset.argb) {
            bytes.put((pixel ushr 16).toByte())
            bytes.put((pixel ushr 8).toByte())
            bytes.put(pixel.toByte())
            bytes.put(255.toByte())
        }
        bytes.rewind()
        assetTextureId = uploadTexture(assetTextureId, asset.width, asset.height, bytes, GLES20.GL_LINEAR)
        return assetTextureId != 0
    }

    private fun uploadTexture(
        textureId: Int,
        width: Int,
        height: Int,
        bytes: ByteBuffer,
        filter: Int
    ): Int {
        val name = textureId.takeIf { it != 0 } ?: IntArray(1).also {
            GLES20.glGenTextures(1, it, 0)
        }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, name)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            bytes
        )
        return if (GLES20.glGetError() == GLES20.GL_NO_ERROR) name else 0
    }

    private companion object {
        const val BYTES_PER_TEXEL = 4
    }
}
