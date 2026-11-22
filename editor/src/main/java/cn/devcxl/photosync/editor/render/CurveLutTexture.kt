package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.CurveLutBuilder
import cn.devcxl.photosync.editor.domain.CurveOps

/**
 * Owns the `1024 x 1 GL_RGBA16F` 1D LUT texture for the 4-channel tone curve (`R`, `G`, `B`, `RGB`),
 * falling back to `GL_RGBA8` when the context is ES 2.0 or rejects half-float texture uploads.
 */
internal class CurveLutTexture {

    /** GL texture name, or `0` when no non-identity curve has been uploaded. */
    var textureId: Int = 0
        private set

    @Volatile
    private var stagedCurve: CurveOps = CurveOps()

    @Volatile
    private var needsUpload: Boolean = false

    private var halfFloatBytes: ByteBuffer? = null
    private var rgba8Bytes: ByteBuffer? = null

    /**
     * Stages [curve] for upload on the GL thread when it differs from the previous curve.
     */
    fun stage(curve: CurveOps) {
        if (curve == stagedCurve && (textureId != 0 || curve.isIdentity())) return
        stagedCurve = curve
        needsUpload = true
    }

    /** Uploads the staged LUT on the GL thread if needed. */
    fun uploadIfNeeded(preferEs3: Boolean) {
        if (!needsUpload) return
        needsUpload = false
        val curve = stagedCurve
        if (curve.isIdentity()) return

        ensureTexture()
        bindParameters()
        if (preferEs3 && uploadHalfFloat(curve)) return
        uploadRgba8(curve)
    }

    /** Forgets the GL texture name after an EGL context reset. */
    fun invalidate() {
        textureId = 0
        needsUpload = !stagedCurve.isIdentity()
    }

    /** Releases the GL texture; call on the GL thread. */
    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
    }

    private fun bindParameters() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun uploadHalfFloat(curve: CurveOps): Boolean {
        val halfBits = CurveLutBuilder.buildHalfFloatLut(curve)
        val capacity = CurveLutBuilder.LUT_SIZE * CurveLutBuilder.CHANNELS * BYTES_PER_HALF
        val bytes = halfFloatBytes ?: ByteBuffer.allocateDirect(capacity)
            .order(ByteOrder.nativeOrder())
            .also { halfFloatBytes = it }
        bytes.rewind()
        for (bits in halfBits) {
            bytes.putShort(bits)
        }
        bytes.rewind()
        GLES20.glGetError()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA16F,
            CurveLutBuilder.LUT_SIZE,
            1,
            0,
            GLES20.GL_RGBA,
            GLES30.GL_HALF_FLOAT,
            bytes
        )
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return true
        Timber.w("GL_RGBA16F curve LUT rejected (error=0x%x); falling back to RGBA8", error)
        bindParameters()
        return false
    }

    private fun uploadRgba8(curve: CurveOps) {
        val floats = CurveLutBuilder.buildFloatLut(curve)
        val capacity = CurveLutBuilder.LUT_SIZE * CurveLutBuilder.CHANNELS
        val bytes = rgba8Bytes ?: ByteBuffer.allocateDirect(capacity)
            .order(ByteOrder.nativeOrder())
            .also { rgba8Bytes = it }
        bytes.rewind()
        for (value in floats) {
            bytes.put((value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().toByte())
        }
        bytes.rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            CurveLutBuilder.LUT_SIZE,
            1,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            bytes
        )
    }

    private companion object {
        private const val BYTES_PER_HALF = 2
    }
}
