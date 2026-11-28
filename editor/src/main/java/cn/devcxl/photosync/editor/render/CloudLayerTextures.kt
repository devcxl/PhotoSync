package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import timber.log.Timber

/**
 * GL textures for the Post-Pass A / Pre-Pass B cloud layer.
 *
 * The wrapper owns two `RGBA8` textures: the cloud pixels and, for local edits, the blend coverage
 * in the red channel. [uploadIfNeeded] runs on the GL thread and is a no-op until [stage] or [clear]
 * changed the binding, so the integration only has to call it once per draw together with the other
 * staged textures.
 *
 * Lifecycle mirrors [SubjectMaskTexture]: [invalidate] forgets the GL names after an EGL context
 * loss while keeping the staged pixels, and [release] deletes the names while a context is current.
 */
class CloudLayerTextures {

    /** GL name of the cloud pixel texture, or `0` when no layer is active. */
    var imageTextureId: Int = 0
        private set

    /** GL name of the coverage texture, or `0` for a whole-frame layer. */
    var maskTextureId: Int = 0
        private set

    /** Width of the uploaded textures. */
    var width: Int = 0
        private set

    /** Height of the uploaded textures. */
    var height: Int = 0
        private set

    /** True when the next draw should sample the cloud textures. */
    val isActive: Boolean
        get() = imageTextureId != 0 && width > 0 && height > 0

    /** True when pixels are waiting for the next GL-thread upload. */
    val isStaged: Boolean
        get() = staged != null

    @Volatile
    private var staged: CloudLayerPixels? = null

    @Volatile
    private var needsUpload: Boolean = false

    private var imageBuffer: ByteBuffer? = null

    private var maskBuffer: ByteBuffer? = null

    /** Stages decoded [layer] pixels, or `null` to unbind the cloud layer. */
    fun stage(layer: CloudLayerPixels?) {
        staged = layer
        needsUpload = true
    }

    /** Unbinds the cloud layer on the next upload. */
    fun clear() = stage(null)

    /** Uploads the staged pixels if they changed. Must run on the GL thread. */
    fun uploadIfNeeded() {
        if (!needsUpload) return
        needsUpload = false
        val layer = staged
        if (layer == null || layer.width <= 0 || layer.height <= 0) {
            clearBinding()
            return
        }
        if (!uploadImage(layer)) {
            Timber.w("The cloud layer texture could not be uploaded")
            releaseTexture(imageTextureId)
            imageTextureId = 0
            clearBinding()
            return
        }
        width = layer.width
        height = layer.height
        if (layer.coverage == null) {
            releaseTexture(maskTextureId)
            maskTextureId = 0
        } else if (!uploadMask(layer)) {
            // A local layer without its coverage texture must not degrade to a whole-frame paste.
            Timber.w("The cloud coverage texture could not be uploaded")
            releaseTexture(imageTextureId)
            releaseTexture(maskTextureId)
            imageTextureId = 0
            maskTextureId = 0
            width = 0
            height = 0
        }
    }

    /** Forgets the GL names after a context loss; staged pixels survive for a re-upload. */
    fun invalidate() {
        imageTextureId = 0
        maskTextureId = 0
        needsUpload = staged != null
    }

    /** Deletes both textures; safe only while a GL context is current. */
    fun release() {
        releaseTexture(imageTextureId)
        releaseTexture(maskTextureId)
        imageTextureId = 0
        maskTextureId = 0
        width = 0
        height = 0
        imageBuffer = null
        maskBuffer = null
    }

    private fun uploadImage(layer: CloudLayerPixels): Boolean {
        val required = layer.argb.size * BYTES_PER_TEXEL
        val bytes = buffer(imageBuffer, required).also { imageBuffer = it }
        bytes.rewind()
        for (pixel in layer.argb) {
            bytes.put((pixel ushr 24).toByte())
            bytes.put((pixel ushr 16).toByte())
            bytes.put((pixel ushr 8).toByte())
            bytes.put(pixel.toByte())
        }
        bytes.rewind()
        imageTextureId = if (imageTextureId == 0) createTexture() else imageTextureId
        return uploadTexture(imageTextureId, layer.width, layer.height, bytes)
    }

    private fun uploadMask(layer: CloudLayerPixels): Boolean {
        val coverage = layer.coverage ?: return false
        val required = coverage.size * BYTES_PER_TEXEL
        val bytes = buffer(maskBuffer, required).also { maskBuffer = it }
        bytes.rewind()
        for (value in coverage) {
            val level = (value.coerceIn(0.0f, 1.0f) * BYTE_MAX + 0.5f).toInt().toByte()
            bytes.put(level)
            bytes.put(level)
            bytes.put(level)
            bytes.put(BYTE_OPAQUE)
        }
        bytes.rewind()
        maskTextureId = if (maskTextureId == 0) createTexture() else maskTextureId
        return uploadTexture(maskTextureId, layer.width, layer.height, bytes)
    }

    private fun uploadTexture(textureId: Int, texelWidth: Int, texelHeight: Int, bytes: ByteBuffer): Boolean {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            texelWidth,
            texelHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            bytes
        )
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun buffer(reusable: ByteBuffer?, required: Int): ByteBuffer =
        reusable?.takeIf { it.capacity() >= required }
            ?: ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        return ids[0]
    }

    private fun clearBinding() {
        width = 0
        height = 0
        releaseTexture(maskTextureId)
        maskTextureId = 0
    }

    private fun releaseTexture(textureId: Int) {
        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private companion object {
        const val BYTES_PER_TEXEL = 4
        const val BYTE_MAX = 255.0f
        const val BYTE_OPAQUE: Byte = 255.toByte()
    }
}
