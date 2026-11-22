package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.LiquifyMesh

/**
 * The Pass A `uWarpTex`: the `128 x 128` liquify displacement grid uploaded as `GL_RG16F`.
 *
 * Tier 1 uploads the half-float grid directly, which keeps the displacement precise to ~1/2048 of a
 * frame. Drivers that reject `GL_RG16F` get a Tier 2 `GL_RGBA8` fallback whose two channels are
 * quantized into `[-MAX_DISPLACEMENT, +MAX_DISPLACEMENT]`; [decodeScale] and [decodeOffset] tell the
 * shader which encoding it is looking at, so the fragment shader stays branch-free.
 *
 * Both tiers use `GL_LINEAR` filtering and `GL_CLAMP_TO_EDGE` wrapping so the CPU resampler's
 * bilinear [LiquifyMesh.displacementAt] produces the same warp as the GPU.
 */
internal class LiquifyWarpTexture {

    /** GL texture name, or `0` when nothing has been uploaded yet. */
    var textureId: Int = 0
        private set

    /** Multiplier the shader applies to the sampled `xy` pair. */
    var decodeScale: Float = 1.0f
        private set

    /** Offset the shader adds after scaling the sampled `xy` pair. */
    var decodeOffset: Float = 0.0f
        private set

    /** True when a staged, non-zero mesh should be sampled by the next Pass A draw. */
    val isWarpActive: Boolean
        get() = !uploadFailed && activeMesh?.let { !it.isZero() } == true

    @Volatile
    private var activeMesh: LiquifyMesh? = null

    @Volatile
    private var needsUpload: Boolean = false

    @Volatile
    private var uploadFailed: Boolean = false

    private var floatBits: ShortArray? = null
    private var halfFloatBytes: ByteBuffer? = null
    private var quantizedBytes: ByteBuffer? = null

    /**
     * Stages [mesh] for the next frame and returns whether a warp is now requested.
     */
    fun stage(mesh: LiquifyMesh?): Boolean {
        activeMesh = mesh
        needsUpload = mesh != null
        uploadFailed = false
        return isWarpActive
    }

    /**
     * Uploads the staged mesh if it changed. Must run on the GL thread.
     *
     * @return True when the upload failed and the warp must stay disabled.
     */
    fun uploadIfNeeded(): Boolean {
        if (!needsUpload) return false
        needsUpload = false
        val mesh = activeMesh
        if (mesh == null) {
            release()
            return false
        }
        uploadFailed = !upload(mesh)
        return uploadFailed
    }

    private fun upload(mesh: LiquifyMesh): Boolean {
        ensureTexture()
        bindParameters()
        if (uploadHalfFloat(mesh)) {
            decodeScale = 1.0f
            decodeOffset = 0.0f
            return true
        }
        return uploadQuantized(mesh)
    }

    /** Forgets the GL name after the EGL context was recreated. */
    fun invalidate() {
        textureId = 0
        needsUpload = activeMesh != null
    }

    /** Deletes the texture; safe to call without a live context only via [invalidate]. */
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

    private fun uploadHalfFloat(mesh: LiquifyMesh): Boolean {
        val bits = floatBits ?: ShortArray(LiquifyMesh.COMPONENT_COUNT).also { floatBits = it }
        mesh.readHalfFloatBitsInto(bits)
        val bytes = halfFloatBytes ?: ByteBuffer
            .allocateDirect(LiquifyMesh.COMPONENT_COUNT * BYTES_PER_HALF)
            .order(ByteOrder.nativeOrder())
            .also { halfFloatBytes = it }
        bytes.rewind()
        for (value in bits) {
            bytes.putShort(value)
        }
        bytes.rewind()
        GLES20.glGetError()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_RG16F,
            mesh.gridSize,
            mesh.gridSize,
            0,
            GLES30.GL_RG,
            GLES30.GL_HALF_FLOAT,
            bytes
        )
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return true
        Timber.w("GL_RG16F liquify mesh rejected (error=0x%x); falling back to RGBA8", error)
        bindParameters()
        return false
    }

    private fun uploadQuantized(mesh: LiquifyMesh): Boolean {
        val bytes = quantizedBytes ?: ByteBuffer
            .allocateDirect(LiquifyMesh.CELL_COUNT * BYTES_PER_RGBA8_TEXEL)
            .order(ByteOrder.nativeOrder())
            .also { quantizedBytes = it }
        bytes.rewind()
        val quantum = 1.0f / (2.0f * LiquifyMesh.MAX_DISPLACEMENT)
        for (cell in 0 until LiquifyMesh.CELL_COUNT) {
            val dx = mesh.displacementRaw(cell * LiquifyMesh.CHANNELS)
            val dy = mesh.displacementRaw(cell * LiquifyMesh.CHANNELS + 1)
            bytes.put(toByte(0.5f + dx * quantum))
            bytes.put(toByte(0.5f + dy * quantum))
            bytes.put(OPAQUE_BYTE)
            bytes.put(OPAQUE_BYTE)
        }
        bytes.rewind()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            mesh.gridSize,
            mesh.gridSize,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            bytes
        )
        if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
            decodeScale = 2.0f * LiquifyMesh.MAX_DISPLACEMENT
            decodeOffset = -LiquifyMesh.MAX_DISPLACEMENT
            return true
        }
        Timber.e("Liquify mesh could not be uploaded; the warp stays disabled")
        decodeScale = 1.0f
        decodeOffset = 0.0f
        return false
    }

    private fun toByte(normalized: Float): Byte =
        (normalized.coerceIn(0.0f, 1.0f) * 255.0f + 0.5f).toInt().toByte()

    private companion object {
        private const val BYTES_PER_HALF = 2
        private const val BYTES_PER_RGBA8_TEXEL = 4
        private const val OPAQUE_BYTE: Byte = 0xFF.toByte()
    }
}
