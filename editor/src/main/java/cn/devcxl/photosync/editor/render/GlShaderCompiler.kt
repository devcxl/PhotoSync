package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

/**
 * Compiles and links GLSL shader programs, logging driver diagnostics through [Timber].
 */
internal object GlShaderCompiler {

    /**
     * Builds a linked program from [vertexSource] and [fragmentSource].
     *
     * @return The program handle, or `0` when compilation or linking failed.
     */
    fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return if (isLinked(program)) program else 0
    }

    private fun isLinked(program: Int): Boolean {
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != 0) return true
        Timber.e("Shader program link failed: %s", GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteProgram(program)
        return false
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != 0) return shader
        Timber.w("Shader compile failed (type=%d): %s", type, GLES20.glGetShaderInfoLog(shader))
        GLES20.glDeleteShader(shader)
        return 0
    }
}
