package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

/**
 * Compiles and executes the linear-light exposure and soft-shoulder GLSL shader program.
 *
 * Uses `#version 300 es` when [preferEs3] is true, and falls back to `#version 100` with
 * identical piecewise sRGB and `tanh` soft-shoulder math if ES 3.0 is unavailable or fails.
 */
internal class ExposureGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uExposureEvLoc: Int = -1
    private var uCompareOriginalLoc: Int = -1
    private var uScaleLoc: Int = -1
    private var uOffsetLoc: Int = -1

    private val quad = QuadMesh()

    fun init(preferEs3: Boolean = true) {
        programId = if (preferEs3) {
            GlShaderCompiler.buildProgram(VERTEX_SHADER_ES3, FRAGMENT_SHADER_ES3).also { id ->
                if (id == 0) {
                    Timber.w("GLES 3.0 shader compile failed; falling back to GLES 2.0 shader")
                }
            }
        } else {
            0
        }
        if (programId == 0) {
            programId = GlShaderCompiler.buildProgram(VERTEX_SHADER_ES2, FRAGMENT_SHADER_ES2)
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
        uOffsetLoc = GLES20.glGetUniformLocation(programId, "uOffset")
    }

    fun draw(
        textureId: Int,
        exposureEv: Float,
        compareOriginal: Boolean,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float = 0.0f,
        offsetY: Float = 0.0f
    ) {
        if (programId == 0 || textureId == 0) return
        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uExposureEvLoc, exposureEv)
        GLES20.glUniform1i(uCompareOriginalLoc, if (compareOriginal) 1 else 0)
        GLES20.glUniform2f(uScaleLoc, scaleX, scaleY)
        GLES20.glUniform2f(uOffsetLoc, offsetX, offsetY)

        quad.draw(aPositionLoc, aTexCoordLoc)
    }

    companion object {
        private val VERTEX_SHADER_ES3 = """
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
            uniform vec2 uOffset;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER_ES2 = """
            precision highp float;
            varying vec2 vTexCoord;
            uniform sampler2D uTex0;
            uniform float uExposureEv;
            uniform int uCompareOriginal;

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

            vec3 tanhApprox(vec3 x) {
                vec3 e2 = exp(clamp(2.0 * x, vec3(-20.0), vec3(20.0)));
                return (e2 - vec3(1.0)) / (e2 + vec3(1.0));
            }

            vec3 softShoulder(vec3 c) {
                float t0 = 0.85;
                float span = 0.15;
                vec3 over = max(c - vec3(t0), vec3(0.0)) / span;
                vec3 rolled = vec3(t0) + span * tanhApprox(over);
                return mix(c, rolled, step(vec3(t0), c));
            }

            void main() {
                vec4 src = texture2D(uTex0, vTexCoord);
                if (uCompareOriginal == 1 || abs(uExposureEv) < 0.0001) {
                    gl_FragColor = src;
                    return;
                }
                vec3 lin = decodeSrgb(src.rgb) * pow(2.0, uExposureEv);
                vec3 rolled = softShoulder(max(lin, vec3(0.0)));
                vec3 outSrgb = clamp(encodeSrgb(rolled), 0.0, 1.0);
                gl_FragColor = vec4(outSrgb, src.a);
            }
        """.trimIndent()
    }
}
