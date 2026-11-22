package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BaseLook

/**
 * Uniform bundle for a single preview draw.
 */
internal data class ExposureDrawParams(
    val textureId: Int,
    val exposureEv: Float,
    val compareOriginal: Boolean,
    val scaleX: Float,
    val scaleY: Float,
    val isLinearInput: Boolean,
    val baseLook: BaseLook,
    val autoBrightGain: Float
)

/**
 * Compiles and executes the exposure + RAW base-look GLSL program.
 *
 * Two input flavours share one shader: sRGB texels (JPEG) are decoded before exposure, while
 * `GL_RGBA16F` RAW texels are already scene-linear so they only receive the auto-bright gain.
 * With [BaseLook.CAMERA_DEFAULT] the shader reproduces the browse-page tone curve and vibrance
 * (`camera_default`), so a zero-parameter edit looks identical to the gallery decode.
 *
 * Uses `#version 300 es` when [init] is called with ES 3.0 enabled and falls back to
 * `#version 100` with the same piecewise sRGB, soft-shoulder and base-look math.
 */
internal class ExposureGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uExposureEvLoc: Int = -1
    private var uCompareOriginalLoc: Int = -1
    private var uScaleLoc: Int = -1
    private var uIsLinearInputLoc: Int = -1
    private var uBaseLookLoc: Int = -1
    private var uAutoBrightGainLoc: Int = -1

    private val quadBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    fun init(preferEs3: Boolean = true) {
        programId = if (preferEs3) {
            buildProgram(
                ExposureShaderSources.VERTEX_SHADER_ES3,
                ExposureShaderSources.FRAGMENT_SHADER_ES3
            ).also { id ->
                if (id == 0) {
                    Timber.w("GLES 3.0 shader compile failed; falling back to GLES 2.0 shader")
                }
            }
        } else {
            0
        }
        if (programId == 0) {
            programId = buildProgram(
                ExposureShaderSources.VERTEX_SHADER_ES2,
                ExposureShaderSources.FRAGMENT_SHADER_ES2
            )
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
        uIsLinearInputLoc = GLES20.glGetUniformLocation(programId, "uIsLinearInput")
        uBaseLookLoc = GLES20.glGetUniformLocation(programId, "uBaseLook")
        uAutoBrightGainLoc = GLES20.glGetUniformLocation(programId, "uAutoBrightGain")
    }

    fun draw(params: ExposureDrawParams) {
        if (programId == 0 || params.textureId == 0) return
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uExposureEvLoc, params.exposureEv)
        GLES20.glUniform1i(uCompareOriginalLoc, if (params.compareOriginal) 1 else 0)
        GLES20.glUniform2f(uScaleLoc, params.scaleX, params.scaleY)
        GLES20.glUniform1i(uIsLinearInputLoc, if (params.isLinearInput) 1 else 0)
        GLES20.glUniform1i(
            uBaseLookLoc,
            if (params.baseLook == BaseLook.CAMERA_DEFAULT) 1 else 0
        )
        GLES20.glUniform1f(uAutoBrightGainLoc, params.autoBrightGain)

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
    }
}
