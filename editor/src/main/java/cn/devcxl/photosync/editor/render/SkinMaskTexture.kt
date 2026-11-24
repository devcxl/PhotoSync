package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.SkinMaskMath

/**
 * The Pass C `uSkinMaskTex`: the `256 x 256` dual-mode skin weight uploaded as `GL_R8`.
 *
 * A single red channel is 64 KiB of texture memory and is exactly what both consumers need — Stage
 * 0 weights the frequency separation with it and Stage 4 multiplies the unsharp mask by
 * `1 - skinMask`. Tier 2 drivers that reject `GL_R8` fall back to `GL_LUMINANCE` through the GLES2
 * path, which samples the same value from `.r`.
 */
internal class SkinMaskTexture {

    /** GL texture name, or `0` when no mask is uploaded. */
    var textureId: Int = 0
        private set

    /** True when a mask is uploaded and should be sampled by the next Pass C draw. */
    val isActive: Boolean
        get() = textureId != 0 && hasMask

    @Volatile
    private var stagedMask: FloatArray? = null

    @Volatile
    private var needsUpload: Boolean = false

    private var hasMask: Boolean = false
    private var bytes: ByteBuffer? = null

    /**
     * Stages [mask] (`256 x 256`, values in `[0, 1]`) for the next frame, or `null` to unbind it.
     *
     * @return True when the next Pass C draw can sample a skin mask.
     */
    fun stage(mask: FloatArray?): Boolean {
        stagedMask = mask
        needsUpload = true
        return mask != null && mask.size == SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE
    }

    /** Uploads the staged mask if it changed. Must run on the GL thread. */
    fun uploadIfNeeded() {
        if (!needsUpload) return
        needsUpload = false
        val mask = stagedMask
        hasMask = false
        if (mask == null || mask.size != SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE) {
            return
        }
        hasMask = upload(mask)
        if (!hasMask) {
            Timber.w("The skin mask texture could not be uploaded; skin smoothing stays off")
        }
    }

    /** Forgets the GL name after the EGL context was recreated. */
    fun invalidate() {
        textureId = 0
        needsUpload = stagedMask != null
    }

    /** Deletes the texture; safe without a live context only via [invalidate]. */
    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        hasMask = false
    }

    private fun upload(mask: FloatArray): Boolean {
        ensureTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val buffer = bytes ?: ByteBuffer
            .allocateDirect(mask.size)
            .order(ByteOrder.nativeOrder())
            .also { bytes = it }
        buffer.rewind()
        for (weight in mask) {
            buffer.put((weight.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().toByte())
        }
        buffer.rewind()
        GLES20.glGetError()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            SkinMaskMath.MASK_SIZE,
            SkinMaskMath.MASK_SIZE,
            0,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        )
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return true
        Timber.w("GL_R8 skin mask rejected (error=0x%x); trying GL_LUMINANCE", error)
        buffer.rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            SkinMaskMath.MASK_SIZE,
            SkinMaskMath.MASK_SIZE,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        )
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
    }
}
