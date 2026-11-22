package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

/**
 * GLES 3.0 Pass A: single-fetch geometry resampling plus pre-crop lateral chromatic-aberration
 * correction.
 *
 * For every output pixel the fragment shader evaluates the pre-compiled inverse homography
 * `H^-1` (`output crop -> perspective quad -> straighten -> rotate/flip`), which lands directly in
 * the source texture's pre-crop UV. It then scales the red and blue lookups by `1 + k * r^2`
 * around the pre-crop optical centre `(0.5, 0.5)`, using green as the reference channel. Because
 * `H^-1` is bounded by the maximum inscribed rectangle, every fetch is guaranteed to be a real
 * pixel, so no black or transparent corner can ever appear.
 */
internal class GeometryCaGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uInverseMatrixLoc: Int = -1
    private var uCaRedLoc: Int = -1
    private var uCaBlueLoc: Int = -1

    private val quad = QuadMesh()

    val isReady: Boolean
        get() = programId != 0

    /**
     * Compiles the program. Only GLES 3.0 contexts can host Pass A because the offscreen
     * `GL_RGBA16F`/`GL_RGBA8` target needs framebuffer objects.
     */
    fun init(): Boolean {
        programId = GlShaderCompiler.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) return false
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uInverseMatrixLoc = GLES20.glGetUniformLocation(programId, "uInverseMatrix")
        uCaRedLoc = GLES20.glGetUniformLocation(programId, "uCaRedK")
        uCaBlueLoc = GLES20.glGetUniformLocation(programId, "uCaBlueK")
        return true
    }

    /**
     * Renders one full-target quad into the currently bound framebuffer.
     *
     * @param textureId Upright source texture.
     * @param columnMajorInverseMatrix `H^-1` packed for `glUniformMatrix3fv`.
     * @param caRedK Radial coefficient for the red channel.
     * @param caBlueK Radial coefficient for the blue channel.
     */
    fun draw(
        textureId: Int,
        columnMajorInverseMatrix: FloatArray,
        caRedK: Float,
        caBlueK: Float
    ) {
        if (programId == 0 || textureId == 0) return
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix3fv(uInverseMatrixLoc, 1, false, columnMajorInverseMatrix, 0)
        GLES20.glUniform1f(uCaRedLoc, caRedK)
        GLES20.glUniform1f(uCaBlueLoc, caBlueK)
        quad.draw(aPositionLoc, aTexCoordLoc)
    }

    private companion object {
        private val VERTEX_SHADER = """
            #version 300 es
            in vec2 aPosition;
            in vec2 aTexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            uniform sampler2D uTex0;
            uniform mat3 uInverseMatrix;
            uniform float uCaRedK;
            uniform float uCaBlueK;
            out vec4 fragColor;

            void main() {
                vec3 mapped = uInverseMatrix * vec3(vTexCoord, 1.0);
                vec2 preCropUv = mapped.xy / mapped.z;
                vec2 delta = preCropUv - vec2(0.5);
                float radiusSquared = dot(delta, delta);
                if (abs(uCaRedK) < 0.000001 && abs(uCaBlueK) < 0.000001) {
                    fragColor = texture(uTex0, preCropUv);
                    return;
                }
                vec2 redUv = vec2(0.5) + delta * (1.0 + uCaRedK * radiusSquared);
                vec2 blueUv = vec2(0.5) + delta * (1.0 + uCaBlueK * radiusSquared);
                vec4 greenReference = texture(uTex0, preCropUv);
                float red = texture(uTex0, redUv).r;
                float blue = texture(uTex0, blueUv).b;
                fragColor = vec4(red, greenReference.g, blue, greenReference.a);
            }
        """.trimIndent()
    }
}
