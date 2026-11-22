package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

import android.opengl.GLES20

/**
 * The interleaved `[x, y, u, v]` triangle strip that every editor pass rasterizes.
 *
 * Texture coordinates are flipped in `v` because bitmap rows are uploaded top-down while GL
 * texture space starts at the bottom-left corner.
 */
internal class QuadMesh {

    private val buffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTICES.size * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTICES)
            position(0)
        }

    /**
     * Binds the position and texture-coordinate attributes and draws the strip.
     */
    fun draw(positionLocation: Int, texCoordLocation: Int) {
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            POSITION_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            buffer
        )
        buffer.position(POSITION_COMPONENTS)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation,
            TEX_COORD_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            buffer
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
    }

    private companion object {
        private const val POSITION_COMPONENTS = 2
        private const val TEX_COORD_COMPONENTS = 2
        private const val COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + TEX_COORD_COMPONENTS
        private const val VERTEX_COUNT = 4
        private const val BYTES_PER_FLOAT = 4
        private const val STRIDE_BYTES = COMPONENTS_PER_VERTEX * BYTES_PER_FLOAT

        private val VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
            1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 0.0f
        )
    }
}
