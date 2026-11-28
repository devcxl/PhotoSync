package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import timber.log.Timber

/**
 * The Pass D `uSubjectTex`: one `RGBA8` texture holding the refined cut-out and its foreground.
 *
 * - `R/G/B` is the FMGE-decontaminated foreground colour, so the shader can rebuild
 *   `a * F + (1 - a) * background` without sampling the already-graded frame.
 * - `A` is the guided-filter-refined alpha, which also becomes the exported alpha of a transparent
 *   PNG.
 *
 * The same class serves both subject spaces:
 * - the **canvas** uploads a source-space composite and passes the Pass A inverse matrix so the
 *   shader maps the current output UV into pre-crop space;
 * - a **bake band** uploads an already band-aligned composite and samples it with the band-local UV.
 */
internal class SubjectMaskTexture {

    /** GL texture name, or `0` when no composite is uploaded. */
    var textureId: Int = 0
        private set

    /** Width of the uploaded composite. */
    var width: Int = 0
        private set

    /** Height of the uploaded composite. */
    var height: Int = 0
        private set

    /** True when a composite is uploaded and should be sampled by the next Pass D draw. */
    val isActive: Boolean
        get() = textureId != 0 && width > 0 && height > 0

    /** True when a composite is waiting for the next GL-thread upload. */
    val isStaged: Boolean
        get() = stagedComposite != null || stagedRgba != null

    @Volatile
    private var stagedComposite: SubjectComposite? = null

    @Volatile
    private var stagedRgba: IntArray? = null

    @Volatile
    private var stagedWidth: Int = 0

    @Volatile
    private var stagedHeight: Int = 0

    @Volatile
    private var needsUpload: Boolean = false

    private var buffer: ByteBuffer? = null

    /** Stages a source-space [composite] for the next draw, or `null` to unbind the subject. */
    fun stage(composite: SubjectComposite?) {
        stagedComposite = composite
        stagedRgba = null
        stagedWidth = composite?.width ?: 0
        stagedHeight = composite?.height ?: 0
        needsUpload = true
    }

    /**
     * Stages already-aligned `RGBA8` texels, used by a bake band whose composite was computed in
     * band-local coordinates.
     */
    fun stageRgba(rgba: IntArray, width: Int, height: Int) {
        if (rgba.size != width * height || width <= 0 || height <= 0) {
            stage(null)
            return
        }
        stagedComposite = null
        stagedRgba = rgba
        stagedWidth = width
        stagedHeight = height
        needsUpload = true
    }

    /** Uploads the staged composite if it changed. Must run on the GL thread. */
    fun uploadIfNeeded() {
        if (!needsUpload) return
        needsUpload = false
        val widthToUpload = stagedWidth
        val heightToUpload = stagedHeight
        val rgba = stagedRgba ?: stagedComposite?.toRgba8()
        if (rgba == null || widthToUpload <= 0 || heightToUpload <= 0) {
            clearBinding()
            return
        }
        val uploaded = upload(rgba, widthToUpload, heightToUpload)
        if (!uploaded) {
            Timber.w("The subject composite texture could not be uploaded")
            clearBinding()
            return
        }
        width = widthToUpload
        height = heightToUpload
    }

    /** Forgets the GL name after the EGL context was recreated. */
    fun invalidate() {
        textureId = 0
        needsUpload = stagedRgba != null || stagedComposite != null
    }

    /** Deletes the texture; safe only while a context is current. */
    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        width = 0
        height = 0
        buffer = null
    }

    private fun clearBinding() {
        width = 0
        height = 0
    }

    private fun upload(rgba: IntArray, texelWidth: Int, texelHeight: Int): Boolean {
        ensureTexture()
        val required = rgba.size * BYTES_PER_TEXEL
        val bytes = buffer?.takeIf { it.capacity() >= required }
            ?: ByteBuffer.allocateDirect(required)
                .order(ByteOrder.nativeOrder())
                .also { buffer = it }
        bytes.rewind()
        for (pixel in rgba) {
            bytes.put((pixel ushr 24).toByte())
            bytes.put((pixel ushr 16).toByte())
            bytes.put((pixel ushr 8).toByte())
            bytes.put(pixel.toByte())
        }
        bytes.rewind()
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

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
    }

    private companion object {
        const val BYTES_PER_TEXEL = 4
    }
}
