package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

/**
 * Copies a display-domain texture to the default framebuffer with the canvas letterbox transform.
 *
 * Pass C cannot draw straight to the screen because its neighbours would be sampled from the
 * viewport clear colour instead of the frame, so the chain renders offscreen and this program
 * presents the result exactly the way `Pass B` used to.
 */
internal class PresentGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uScaleLoc: Int = -1
    private var uOffsetLoc: Int = -1

    private val quad = QuadMesh()

    val isReady: Boolean
        get() = programId != 0

    /** Compiles the presentation program. */
    fun init() {
        programId = GlShaderCompiler.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) return
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uScaleLoc = GLES20.glGetUniformLocation(programId, "uScale")
        uOffsetLoc = GLES20.glGetUniformLocation(programId, "uOffset")
    }

    /** Draws [textureId] into the currently bound framebuffer. */
    fun draw(textureId: Int, scaleX: Float, scaleY: Float, offsetX: Float, offsetY: Float) {
        if (programId == 0 || textureId == 0) return
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)
        GLES20.glUniform2f(uOffsetLoc, offsetX, offsetY)
        quad.draw(aPositionLoc, aTexCoordLoc)
    }

    /** Releases the program. Safe to call without a live context. */
    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }

    private companion object {
        private val VERTEX_SHADER = """
            #version 300 es
            in vec2 aPosition;
            in vec2 aTexCoord;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uTex0;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex0, vTexCoord);
            }
        """.trimIndent()
    }
}
