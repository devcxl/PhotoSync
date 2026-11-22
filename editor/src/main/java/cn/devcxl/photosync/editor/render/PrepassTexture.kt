package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import android.opengl.GLES30

import cn.devcxl.photosync.editor.domain.HalfFloatBits
import cn.devcxl.photosync.editor.domain.PrepassMath

/**
 * The `uPrepassTex` payload: a `gridSize x gridSize` `RGBA` texture packing the Clarity guide (`r`),
 * the Texture guide (`g`), the coarse transmission (`b`) and a constant `1` alpha.
 *
 * Tier 1 uploads `GL_RGBA16F`, which keeps a `0.05` transmission distinguishable from `0.06` in a
 * deep shadow. A driver without half-float uploads falls back to `GL_RGBA8`; the guide layers are
 * `[0, 1]` values, so 8 bits is coarse but never out of range.
 */
internal class PrepassTexture {

    /** GL texture name, or `0` before the first Pre-pass upload. */
    var textureId: Int = 0
        private set

    /** Side of the uploaded analysis grid, or `0` when nothing is uploaded. */
    var gridSize: Int = 0
        private set

    val isReady: Boolean
        get() = textureId != 0 && gridSize > 0

    /** Uploads [analysis] as an `RGBA16F` (or `RGBA8`) texture. */
    fun upload(analysis: PrepassMath.Analysis, allowHalfFloat: Boolean) {
        ensureTextureSize(analysis.gridSize)
        if (textureId == 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (allowHalfFloat) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA16F,
                analysis.gridSize,
                analysis.gridSize,
                0,
                GLES20.GL_RGBA,
                GLES30.GL_HALF_FLOAT,
                toHalfFloatBuffer(analysis.packedGuides)
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA8,
                analysis.gridSize,
                analysis.gridSize,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                toByteBuffer(analysis.packedGuides)
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Releases the texture. Safe to call without a live context. */
    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        gridSize = 0
    }

    private fun ensureTextureSize(grid: Int) {
        if (textureId == 0) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
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
        }
        gridSize = grid
    }

    private fun toHalfFloatBuffer(values: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * BYTES_PER_HALF_TEXEL)
            .order(ByteOrder.nativeOrder())
        for (value in values) buffer.putShort(HalfFloatBits.toBits(value))
        buffer.position(0)
        return buffer
    }

    private fun toByteBuffer(values: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * BYTES_PER_TEXEL)
            .order(ByteOrder.nativeOrder())
        for (value in values) {
            val quantized = (value.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt()
            buffer.put(quantized.coerceIn(0, 255).toByte())
        }
        buffer.position(0)
        return buffer
    }

    private companion object {
        private const val BYTES_PER_TEXEL = 4
        private const val BYTES_PER_HALF_TEXEL = 2
    }
}
