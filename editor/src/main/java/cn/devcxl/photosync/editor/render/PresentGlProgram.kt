package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

import cn.devcxl.photosync.editor.domain.PassDEffectsMath

/**
 * Copies a display-domain texture (such as Pass C output) to the 8-bit screen framebuffer with the
 * canvas letterbox transform and `1/255` triangular dithering.
 */
internal class PresentGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uScaleLoc: Int = -1
    private var uOffsetLoc: Int = -1
    private var uDitherAmpLoc: Int = -1

    private val quad = QuadMesh()

    val isReady: Boolean
        get() = programId != 0

    /** Compiles the presentation program. */
    fun init() {
        programId = GlShaderCompiler.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) {
            GlCapabilities.markMinimalFallback("PresentGlProgram")
            return
        }
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uScaleLoc = GLES20.glGetUniformLocation(programId, "uScale")
        uOffsetLoc = GLES20.glGetUniformLocation(programId, "uOffset")
        uDitherAmpLoc = GLES20.glGetUniformLocation(programId, "uDitherAmp")
    }

    /** Draws [textureId] into the currently bound framebuffer with triangular dithering. */
    fun draw(
        textureId: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        ditherAmp: Float = PassDEffectsMath.DITHER_AMPLITUDE
    ) {
        if (programId == 0 || textureId == 0) return
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)
        GLES20.glUniform2f(uOffsetLoc, offsetX, offsetY)
        GLES20.glUniform1f(uDitherAmpLoc, ditherAmp)
        quad.draw(aPositionLoc, aTexCoordLoc, QuadOrientation.SCREEN)
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
            uniform float uDitherAmp;
            out vec4 fragColor;

            float triDither(vec2 position) {
                float n1 = fract(sin(dot(position, vec2(12.9898, 78.233))) * 43758.5453);
                float n2 = fract(sin(dot(position + vec2(1.0, 1.0), vec2(39.346, 11.135))) * 43758.5453);
                return (n1 - n2) * uDitherAmp;
            }

            void main() {
                vec4 src = texture(uTex0, vTexCoord);
                vec3 dithered = clamp(src.rgb + vec3(triDither(gl_FragCoord.xy)), 0.0, 1.0);
                fragColor = vec4(dithered, src.a);
            }
        """.trimIndent()
    }
}
