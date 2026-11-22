package cn.devcxl.photosync.editor.lut

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.HalfFloatBits
import cn.devcxl.photosync.editor.domain.Lut3dSamples

/**
 * Owns the `33 x 33 x 33 GL_RGBA16F` `GL_TEXTURE_3D` the Pass D shader samples with `texelFetch`.
 *
 * `GL_NEAREST` is mandatory: tetrahedral interpolation fetches the eight integer corners itself, so
 * any hardware filtering would smear neighbouring cells. Uploads are deferred to the GL thread via
 * [uploadIfNeeded] because [stage] runs on the UI thread.
 */
internal class Lut3dTexture {

    /** GL texture name, or `0` when no LUT is bound. */
    var textureId: Int = 0
        private set

    /** Samples per axis of the bound texture, or `0` when nothing is uploaded. */
    var sampleSize: Int = 0
        private set

    /** True when the Pass D shader can sample a 3D LUT this frame. */
    val isReady: Boolean
        get() = textureId != 0 && sampleSize >= MIN_SAMPLE_SIZE

    @Volatile
    private var staged: Lut3dSamples? = null

    @Volatile
    private var needsUpload: Boolean = false

    private var payload: ByteBuffer? = null

    /** Stages [samples] for the next frame; `null` unbinds the LUT on the GL thread. */
    fun stage(samples: Lut3dSamples?) {
        if (staged === samples) return
        staged = samples
        // Both directions are reconciled on the GL thread: staging runs on the UI thread, where
        // creating or deleting a texture name is illegal.
        needsUpload = true
    }

    /** Uploads or unbinds the staged samples on the GL thread when they changed. */
    fun uploadIfNeeded(preferEs3: Boolean) {
        if (!needsUpload) return
        needsUpload = false
        val samples = staged
        if (samples == null || !preferEs3 || samples.size < MIN_SAMPLE_SIZE) {
            deleteTexture()
            return
        }
        if (!upload(samples)) {
            deleteTexture()
            Timber.w("3D LUT texture upload failed; Pass D will render without the LUT")
        }
    }

    /** Forgets the GL name after the EGL context was recreated. */
    fun invalidate() {
        textureId = 0
        sampleSize = 0
        needsUpload = staged != null
    }

    /** Deletes the GL texture when tearing down an active GL context. */
    fun release() {
        deleteTexture()
        payload = null
    }

    private fun upload(samples: Lut3dSamples): Boolean {
        ensureTexture()
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)

        val bytes = fillPayload(samples)
        GLES20.glGetError()
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGBA16F,
            samples.size,
            samples.size,
            samples.size,
            0,
            GLES20.GL_RGBA,
            GLES30.GL_HALF_FLOAT,
            bytes
        )
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Timber.w("GL_RGBA16F 3D LUT upload rejected (error=0x%x)", error)
            return false
        }
        sampleSize = samples.size
        return true
    }

    private fun fillPayload(samples: Lut3dSamples): ByteBuffer {
        val texelCount = samples.size * samples.size * samples.size
        val byteCount = texelCount * COMPONENTS_PER_TEXEL * BYTES_PER_HALF
        val buffer = (payload ?: ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()))
            .also { payload = it }
        buffer.rewind()
        for (texel in 0 until texelCount) {
            val base = texel * COMPONENTS_PER_TEXEL
            buffer.putShort(HalfFloatBits.toBits(samples.rgba[base].coerceIn(0.0f, 1.0f)))
            buffer.putShort(HalfFloatBits.toBits(samples.rgba[base + 1].coerceIn(0.0f, 1.0f)))
            buffer.putShort(HalfFloatBits.toBits(samples.rgba[base + 2].coerceIn(0.0f, 1.0f)))
            buffer.putShort(HalfFloatBits.toBits(OPAQUE))
        }
        buffer.rewind()
        return buffer
    }

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
    }

    private fun deleteTexture() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        sampleSize = 0
    }

    private companion object {
        private const val MIN_SAMPLE_SIZE = 2
        private const val COMPONENTS_PER_TEXEL = 4
        private const val BYTES_PER_HALF = 2
        private const val OPAQUE = 1.0f
    }
}
