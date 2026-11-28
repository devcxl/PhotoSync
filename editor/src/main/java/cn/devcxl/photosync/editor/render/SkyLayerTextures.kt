package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.vision.SkyMask

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

    /** True when a per-band refined mask replaced the source-space mask. */
    var maskLocalSpace: Boolean = false
        private set

    /** Texture holding the bake band's guided-filter-refined mask, or `0`. */
    var bandMaskTextureId: Int = 0
        private set

    /** The mask the next draw must bind: a band plane when present, otherwise the source mask. */
    val drawMaskTextureId: Int
        get() = if (maskLocalSpace) bandMaskTextureId else maskTextureId

    /** True when the mask can be sampled by the next draw. */
    val isActive: Boolean
        get() = drawMaskTextureId != 0

    /** True when a replacement image is bound. */
    val hasAsset: Boolean
        get() = assetTextureId != 0

    @Volatile
    private var staged: SkyLayerPixels? = null

    @Volatile
    private var needsUpload: Boolean = false

    private var stagedBandMask: FloatArray? = null
    private var stagedBandWidth: Int = 0
    private var stagedBandHeight: Int = 0

    private var maskBuffer: ByteBuffer? = null
    private var assetBuffer: ByteBuffer? = null
    private var bandMaskBuffer: ByteBuffer? = null

    /** Stages [layer] for the next GL-thread upload, or `null` to unbind the sky. */
    fun stage(layer: SkyLayerPixels?) {
        staged = layer
        stagedBandMask = null
        maskLocalSpace = false
        needsUpload = true
    }

    /**
     * Stages an already band-aligned alpha produced by the bake's per-band guided filter.
     *
     * A `null` or mismatched plane drops the band mask and makes the next draw fall back to the
     * bilinear source-space mask, which keeps an OOM from aborting the export.
     */
    fun stageBandMask(alpha: FloatArray?, width: Int, height: Int) {
        val valid = alpha != null && width > 0 && height > 0 && alpha.size == width * height
        stagedBandMask = if (valid) alpha else null
        stagedBandWidth = if (valid) width else 0
        stagedBandHeight = if (valid) height else 0
        if (!valid) maskLocalSpace = false
    }

    /** Uploads the staged layer if it changed. Must run on the GL thread. */
    fun uploadIfNeeded() {
        val band = stagedBandMask
        if (band != null) {
            uploadBandMask(band, stagedBandWidth, stagedBandHeight)
            return
        }
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
        bandMaskTextureId = 0
        maskLocalSpace = false
        needsUpload = staged != null
    }

    /** Deletes both textures; safe only while a context is current. */
    fun release() {
        val ids = intArrayOf(maskTextureId, assetTextureId, bandMaskTextureId)
            .filter { it != 0 }
            .toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        maskTextureId = 0
        assetTextureId = 0
        bandMaskTextureId = 0
        maskLocalSpace = false
        maskBuffer = null
        assetBuffer = null
        bandMaskBuffer = null
    }

    private fun clearBindings() {
        maskTextureId = 0
        assetTextureId = 0
        bandMaskTextureId = 0
        maskLocalSpace = false
    }

    private fun uploadBandMask(alpha: FloatArray, width: Int, height: Int) {
        val required = width * height * BYTES_PER_TEXEL
        val bytes = bandMaskBuffer?.takeIf { it.capacity() >= required }
            ?: ByteBuffer.allocateDirect(required)
                .order(ByteOrder.nativeOrder())
                .also { bandMaskBuffer = it }
        bytes.rewind()
        for (value in alpha) {
            val level = (value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            bytes.put(level.toByte())
            bytes.put(level.toByte())
            bytes.put(level.toByte())
            bytes.put(255.toByte())
        }
        bytes.rewind()
        bandMaskTextureId = uploadTexture(bandMaskTextureId, width, height, bytes, GLES20.GL_LINEAR)
        maskLocalSpace = bandMaskTextureId != 0
        if (!maskLocalSpace) {
            Timber.w("The sky band mask could not be uploaded; falling back to the source mask")
        }
    }

    private fun uploadMask(mask: SkyMask): Boolean {
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
