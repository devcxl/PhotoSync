package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

import android.opengl.GLES20

/**
 * Vertical texture-coordinate mapping of a full-frame quad.
 *
 * Every 2D texture in the editor (uploaded bitmaps, linear RAW buffers, `uWarpTex`, `uSkinMaskTex`,
 * `uPrepassTex`, and every `OffscreenColorTarget` FBO) uses a **top-down** row layout where
 * `v = 0` is the image top and `v = 1` is the image bottom:
 * - [OFFSCREEN] maps FBO row 0 (`gl_Position.y = -1`) to `v = 0` and row `H - 1` (`y = +1`) to
 *   `v = 1`, so any chain of 0..N offscreen FBO passes keeps the image strictly top-down without
 *   ever flipping vertically.
 * - [SCREEN] flips `v` once at final presentation onto the default framebuffer (`FBO 0`), where
 *   `y = +1` is the top of the screen and must sample `v = 0`.
 */
internal enum class QuadOrientation {
    OFFSCREEN,
    SCREEN
}

/**
 * The interleaved `[x, y, u, v]` triangle strip that every editor pass rasterizes.
 */
internal class QuadMesh {

    private val offscreenBuffer: FloatBuffer = allocateBuffer(OFFSCREEN_VERTICES)
    private val screenBuffer: FloatBuffer = allocateBuffer(SCREEN_VERTICES)

    /**
     * Binds the position and texture-coordinate attributes and draws the strip with [orientation].
     */
    fun draw(
        positionLocation: Int,
        texCoordLocation: Int,
        orientation: QuadOrientation
    ) {
        val buffer = when (orientation) {
            QuadOrientation.OFFSCREEN -> offscreenBuffer
            QuadOrientation.SCREEN -> screenBuffer
        }
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

    internal companion object {
        private const val POSITION_COMPONENTS = 2
        private const val TEX_COORD_COMPONENTS = 2
        private const val COMPONENTS_PER_VERTEX = POSITION_COMPONENTS + TEX_COORD_COMPONENTS
        private const val VERTEX_COUNT = 4
        private const val BYTES_PER_FLOAT = 4
        private const val STRIDE_BYTES = COMPONENTS_PER_VERTEX * BYTES_PER_FLOAT

        /** Identity FBO layout: `y = -1 -> v = 0` (image top), `y = +1 -> v = 1` (image bottom). */
        private val OFFSCREEN_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        )

        /** Default framebuffer presentation: `y = +1 -> v = 0` (top), `y = -1 -> v = 1` (bottom). */
        private val SCREEN_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
            1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 0.0f
        )

        /** Returns a copy of the interleaved vertex data for [orientation] (for unit tests). */
        internal fun verticesFor(orientation: QuadOrientation): FloatArray = when (orientation) {
            QuadOrientation.OFFSCREEN -> OFFSCREEN_VERTICES.copyOf()
            QuadOrientation.SCREEN -> SCREEN_VERTICES.copyOf()
        }

        private fun allocateBuffer(vertices: FloatArray): FloatBuffer = ByteBuffer
            .allocateDirect(vertices.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
    }
}
