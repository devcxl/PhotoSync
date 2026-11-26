package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

/**
 * The Pass B/C `uSubjectMaskTex`: the refined subject alpha uploaded as a single `GL_R8` channel.
 *
 * It is deliberately separate from [SubjectMaskTexture]:
 * - Pass D needs the whole `RGBA` composite (`RBG = foreground`, `A = alpha`) to rebuild the cut-out;
 * - Pass B relight and the terminal Pass C bokeh only need the alpha, and a `1024 x 1024` `R8`
 *   texture is 1 MiB instead of 4 MiB on every preview frame.
 *
 * The same class serves both subject spaces, mirroring `SubjectMaskTexture`: the canvas uploads a
 * source-space alpha and maps it through the Pass A inverse matrix, while a bake band uploads an
 * already band-aligned alpha sampled with the band-local UV.
 */
internal class SubjectWeightTexture {

    /** GL texture name, or `0` when no mask is uploaded. */
    var textureId: Int = 0
        private set

    /** Width of the uploaded alpha plane. */
    var width: Int = 0
        private set

    /** Height of the uploaded alpha plane. */
    var height: Int = 0
        private set

    /** True when a mask is uploaded and should be sampled by the next Pass B/C draw. */
    val isActive: Boolean
        get() = textureId != 0 && width > 0 && height > 0

    @Volatile
    private var stagedAlpha: FloatArray? = null

    @Volatile
    private var stagedWidth: Int = 0

    @Volatile
    private var stagedHeight: Int = 0

    @Volatile
    private var needsUpload: Boolean = false

    private var bytes: ByteBuffer? = null

    /**
     * Stages [alpha] for the next frame, or `null` to unbind the subject mask.
     *
     * @return True when the staged buffer matches the declared grid.
     */
    fun stage(alpha: FloatArray?, width: Int, height: Int): Boolean {
        val valid = alpha != null && width > 0 && height > 0 && alpha.size == width * height
        stagedAlpha = if (valid) alpha else null
        stagedWidth = if (valid) width else 0
        stagedHeight = if (valid) height else 0
        needsUpload = true
        return valid
    }

    /** Stages the refined alpha of [composite], or unbinds the mask when it is `null`. */
    fun stage(composite: SubjectComposite?): Boolean =
        stage(composite?.alpha, composite?.width ?: 0, composite?.height ?: 0)

    /** Uploads the staged alpha if it changed. Must run on the GL thread. */
    fun uploadIfNeeded() {
        if (!needsUpload) return
        needsUpload = false
        val alpha = stagedAlpha
        val widthToUpload = stagedWidth
        val heightToUpload = stagedHeight
        if (alpha == null || widthToUpload <= 0 || heightToUpload <= 0) {
            clearBinding()
            return
        }
        if (!upload(alpha, widthToUpload, heightToUpload)) {
            Timber.w("The subject weight texture could not be uploaded")
            clearBinding()
            return
        }
        width = widthToUpload
        height = heightToUpload
    }

    /** Forgets the GL name after the EGL context was recreated. */
    fun invalidate() {
        textureId = 0
        needsUpload = stagedAlpha != null
    }

    /** Deletes the texture; safe only while a context is current. */
    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        width = 0
        height = 0
        bytes = null
    }

    private fun clearBinding() {
        width = 0
        height = 0
    }

    private fun upload(alpha: FloatArray, texelWidth: Int, texelHeight: Int): Boolean {
        ensureTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val buffer = bytes?.takeIf { it.capacity() >= alpha.size }
            ?: ByteBuffer.allocateDirect(alpha.size)
                .order(ByteOrder.nativeOrder())
                .also { bytes = it }
        buffer.rewind()
        for (value in alpha) {
            buffer.put((value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().toByte())
        }
        buffer.rewind()
        GLES20.glGetError()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            texelWidth,
            texelHeight,
            0,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        )
        if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return true
        Timber.w("GL_R8 subject mask rejected; trying GL_LUMINANCE")
        buffer.rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            texelWidth,
            texelHeight,
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
