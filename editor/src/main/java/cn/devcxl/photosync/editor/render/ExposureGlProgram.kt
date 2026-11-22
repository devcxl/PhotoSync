package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

import android.opengl.GLES20
import timber.log.Timber

/**
 * Compiles and executes the linear-light exposure and soft-shoulder GLSL shader program.
 *
 * Uses `#version 300 es` by default and automatically falls back to `#version 100` if the
 * driver rejects ES 3.0 shader compilation (Tier 3 resilience).
 */
internal class ExposureGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uExposureEvLoc: Int = -1
    private var uCompareOriginalLoc: Int = -1
    private var uScaleLoc: Int = -1

    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    fun init() {
        programId = buildProgram(VERTEX_SHADER_ES3, FRAGMENT_SHADER_ES3)
        if (programId == 0) {
            Timber.w("GLES 3.0 shader compile failed; falling back to GLES 2.0 exposure shader")
            programId = buildProgram(VERTEX_SHADER_ES2, FRAGMENT_SHADER_ES2)
        }
        if (programId == 0) {
            Timber.e("Failed to compile both GLES 3.0 and fallback exposure shaders")
            return
        }
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uExposureEvLoc = GLES20.glGetUniformLocation(programId, "uExposureEv")
        uCompareOriginalLoc = GLES20.glGetUniformLocation(programId, "uCompareOriginal")
        uScaleLoc = GLES20.glGetUniformLocation(programId, "uScale")
    }

    fun draw(
        textureId: Int,
        exposureEv: Float,
        compareOriginal: Boolean,
        scaleX: Float,
        scaleY: Float
    ) {
        if (programId == 0 || textureId == 0) return
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uExposureEvLoc, exposureEv)
        GLES20.glUniform1i(uCompareOriginalLoc, if (compareOriginal) 1 else 0)
        GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(program)
            Timber.e("Shader program link failed: %s", info)
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            Timber.w("Shader compile failed (type=%d): %s", type, info)
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 0.0f
        )

        private val VERTEX_SHADER_ES3 = """
            #version 300 es
            in vec2 aPosition;
            in vec2 aTexCoord;
            uniform vec2 uScale;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_ES3 = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uTex0;
            uniform float uExposureEv;
            uniform int uCompareOriginal;
            out vec4 fragColor;

            vec3 decodeSrgb(vec3 c) {
                vec3 lo = c / 12.92;
                vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
                return mix(lo, hi, step(vec3(0.04045), c));
            }

            vec3 encodeSrgb(vec3 c) {
                vec3 lo = c * 12.92;
                vec3 hi = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
                return mix(lo, hi, step(vec3(0.0031308), c));
            }

            vec3 softShoulder(vec3 c) {
                const float t0 = 0.85;
                const float span = 0.15;
                vec3 over = max(c - vec3(t0), vec3(0.0)) / span;
                vec3 rolled = vec3(t0) + span * tanh(over);
                return mix(c, rolled, step(vec3(t0), c));
            }

            void main() {
                vec4 src = texture(uTex0, vTexCoord);
                if (uCompareOriginal == 1 || abs(uExposureEv) < 0.0001) {
                    fragColor = src;
                    return;
                }
                vec3 lin = decodeSrgb(src.rgb) * exp2(uExposureEv);
                vec3 rolled = softShoulder(max(lin, vec3(0.0)));
                vec3 outSrgb = clamp(encodeSrgb(rolled), 0.0, 1.0);
                fragColor = vec4(outSrgb, src.a);
            }
        """.trimIndent()

        private val VERTEX_SHADER_ES2 = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform vec2 uScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_ES2 = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTex0;
            uniform float uExposureEv;
            uniform int uCompareOriginal;

            void main() {
                vec4 src = texture2D(uTex0, vTexCoord);
                if (uCompareOriginal == 1 || abs(uExposureEv) < 0.0001) {
                    gl_FragColor = src;
                    return;
                }
                vec3 lin = pow(max(src.rgb, vec3(0.0)), vec3(2.2)) * pow(2.0, uExposureEv);
                vec3 rolled = lin / (vec3(1.0) + max(lin - vec3(0.85), vec3(0.0)));
                vec3 outSrgb = clamp(pow(max(rolled, vec3(0.0)), vec3(1.0 / 2.2)), 0.0, 1.0);
                gl_FragColor = vec4(outSrgb, src.a);
            }
        """.trimIndent()
    }
}
