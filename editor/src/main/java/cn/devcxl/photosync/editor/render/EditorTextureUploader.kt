package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import timber.log.Timber

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Texture creation and upload for the editor canvas.
 *
 * Linear RAW buffers are uploaded straight from their off-heap `RGBA16F` bytes so no JVM heap
 * copy of the image is ever produced. Drivers that reject `GL_RGBA16F` fall back to 8-bit
 * `GL_RGBA` texels (Tier 2), which costs shadow precision but keeps the editor usable.
 */
internal object EditorTextureUploader {

    private const val CHANNELS = 4
    private const val BYTES_PER_RGBA8_TEXEL = 4
    private const val OPAQUE_BYTE: Byte = 0xFF.toByte()

    /**
     * Uploads an sRGB [bitmap] into a `GL_RGBA` texture, reusing [existingTextureId] when set.
     */
    fun uploadBitmap(bitmap: Bitmap, existingTextureId: Int): Int {
        val textureId = bindNewTexture(existingTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return textureId
    }

    /**
     * Uploads [buffer] as `GL_RGBA16F` half-float texels.
     *
     * @return The texture id, or `0` when the driver rejects the half-float upload (the caller
     *   should retry with [uploadLinearAsBytes]).
     */
    fun uploadLinearHalfFloat(buffer: LinearRawBuffer, existingTextureId: Int): Int {
        val textureId = bindNewTexture(existingTextureId)
        val texels = buffer.rgba16fBuffer.duplicate()
        texels.order(ByteOrder.nativeOrder())
        texels.rewind()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA16F,
            buffer.width,
            buffer.height,
            0,
            GLES20.GL_RGBA,
            GLES30.GL_HALF_FLOAT,
            texels
        )
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return textureId
        Timber.w("GL_RGBA16F upload rejected (error=0x%x); falling back to 8-bit texels", error)
        releaseTexture(textureId)
        return 0
    }

    /**
     * Tier 2 fallback: quantizes a linear [buffer] into 8-bit `GL_RGBA` texels.
     */
    fun uploadLinearAsBytes(buffer: LinearRawBuffer, existingTextureId: Int): Int {
        val textureId = bindNewTexture(existingTextureId)
        val texels = quantizeToRgba8(buffer)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            buffer.width,
            buffer.height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            texels
        )
        return textureId
    }

    /** Deletes [textureId] if it was generated. */
    fun releaseTexture(textureId: Int) {
        if (textureId == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun bindNewTexture(existingTextureId: Int): Int {
        val ids = IntArray(1)
        if (existingTextureId != 0) {
            ids[0] = existingTextureId
        } else {
            GLES20.glGenTextures(1, ids, 0)
        }
        val textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        return textureId
    }

    private fun quantizeToRgba8(buffer: LinearRawBuffer): ByteBuffer {
        val texels = ByteBuffer.allocateDirect(
            buffer.width * buffer.height * BYTES_PER_RGBA8_TEXEL
        ).order(ByteOrder.nativeOrder())
        val sample = FloatArray(CHANNELS)
        val pixelCount = buffer.width * buffer.height
        for (index in 0 until pixelCount) {
            buffer.readPixel(index, sample)
            texels.put(toByte(sample[0]))
            texels.put(toByte(sample[1]))
            texels.put(toByte(sample[2]))
            texels.put(OPAQUE_BYTE)
        }
        texels.rewind()
        return texels
    }

    private fun toByte(value: Float): Byte =
        (value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().toByte()
}
