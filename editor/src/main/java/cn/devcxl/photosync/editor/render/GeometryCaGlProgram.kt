package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

/**
 * Immutable per-draw uniforms of the Pass A program.
 */
internal data class GeometryDrawParams(
    val sourceTextureId: Int,
    val columnMajorInverseMatrix: FloatArray,
    val caRedK: Float,
    val caBlueK: Float,
    val srcAspect: Float,
    val encodeSrgbWithDither: Boolean,
    val warpTextureId: Int = 0,
    val warpDecodeScale: Float = 1.0f,
    val warpDecodeOffset: Float = 0.0f
)

/**
 * GLES 3.0 Pass A: single-fetch geometry resampling, liquify warp and pre-crop lateral
 * chromatic-aberration correction.
 *
 * Evaluates the pre-compiled inverse homography `H^-1`, adds the liquify displacement sampled from
 * the `128 x 128` `uWarpTex` (the seam the portrait MLS and body warps will also feed), then scales
 * the red and blue lookups by `1 + k * r^2` in aspect-corrected circular physical coordinates
 * around `(0.5, 0.5)`.
 *
 * When writing a 16-bit linear source into a Tier 2 `GL_RGBA8` intermediate target, sets
 * `uEncodeSrgbWithDither = 1` so dark shadows are stored in sRGB with triangular dithering
 * rather than 8-bit linear quantization.
 */
internal class GeometryCaGlProgram {

    private var programId: Int = 0
    private var aPositionLoc: Int = -1
    private var aTexCoordLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var uWarpTextureLoc: Int = -1
    private var uHasWarpLoc: Int = -1
    private var uWarpScaleLoc: Int = -1
    private var uWarpOffsetLoc: Int = -1
    private var uInverseMatrixLoc: Int = -1
    private var uCaRedLoc: Int = -1
    private var uCaBlueLoc: Int = -1
    private var uSrcAspectLoc: Int = -1
    private var uEncodeSrgbDitherLoc: Int = -1

    private val quad = QuadMesh()

    val isReady: Boolean
        get() = programId != 0

    fun init(): Boolean {
        programId = GlShaderCompiler.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) return false
        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(programId, "uTex0")
        uWarpTextureLoc = GLES20.glGetUniformLocation(programId, "uWarpTex")
        uHasWarpLoc = GLES20.glGetUniformLocation(programId, "uHasWarpTex")
        uWarpScaleLoc = GLES20.glGetUniformLocation(programId, "uWarpScale")
        uWarpOffsetLoc = GLES20.glGetUniformLocation(programId, "uWarpOffset")
        uInverseMatrixLoc = GLES20.glGetUniformLocation(programId, "uInverseMatrix")
        uCaRedLoc = GLES20.glGetUniformLocation(programId, "uCaRedK")
        uCaBlueLoc = GLES20.glGetUniformLocation(programId, "uCaBlueK")
        uSrcAspectLoc = GLES20.glGetUniformLocation(programId, "uSrcAspect")
        uEncodeSrgbDitherLoc = GLES20.glGetUniformLocation(programId, "uEncodeSrgbWithDither")
        return true
    }

    fun draw(params: GeometryDrawParams) {
        if (programId == 0 || params.sourceTextureId == 0) return
        GLES20.glUseProgram(programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.sourceTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        bindWarpTexture(params)
        GLES20.glUniformMatrix3fv(uInverseMatrixLoc, 1, false, params.columnMajorInverseMatrix, 0)
        GLES20.glUniform1f(uCaRedLoc, params.caRedK)
        GLES20.glUniform1f(uCaBlueLoc, params.caBlueK)
        GLES20.glUniform1f(uSrcAspectLoc, params.srcAspect)
        GLES20.glUniform1i(
            uEncodeSrgbDitherLoc,
            if (params.encodeSrgbWithDither) 1 else 0
        )
        quad.draw(aPositionLoc, aTexCoordLoc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun bindWarpTexture(params: GeometryDrawParams) {
        val hasWarp = params.warpTextureId != 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, params.warpTextureId)
        GLES20.glUniform1i(uWarpTextureLoc, 1)
        GLES20.glUniform1i(uHasWarpLoc, if (hasWarp) 1 else 0)
        GLES20.glUniform1f(uWarpScaleLoc, params.warpDecodeScale)
        GLES20.glUniform1f(uWarpOffsetLoc, params.warpDecodeOffset)
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
            uniform sampler2D uWarpTex;
            uniform int uHasWarpTex;
            uniform float uWarpScale;
            uniform float uWarpOffset;
            uniform mat3 uInverseMatrix;
            uniform float uCaRedK;
            uniform float uCaBlueK;
            uniform float uSrcAspect;
            uniform int uEncodeSrgbWithDither;
            out vec4 fragColor;

            vec3 encodeSrgb(vec3 c) {
                vec3 v = clamp(c, 0.0, 1.0);
                vec3 lo = v * 12.92;
                vec3 hi = 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055;
                return mix(lo, hi, step(vec3(0.0031308), v));
            }

            float triNoise(vec2 xy) {
                float n1 = fract(sin(dot(xy, vec2(12.9898, 78.233))) * 43758.5453);
                float n2 = fract(sin(dot(xy + vec2(1.0, 1.0), vec2(39.346, 11.135))) * 43758.5453);
                return (n1 + n2) - 1.0;
            }

            void main() {
                vec3 mapped = uInverseMatrix * vec3(vTexCoord, 1.0);
                vec2 preCropUv = mapped.xy / mapped.z;
                // Liquify (and later face/body) inverse-mapping displacement, in pre-crop UV units.
                if (uHasWarpTex == 1) {
                    vec2 warp = texture(uWarpTex, preCropUv).xy * uWarpScale + vec2(uWarpOffset);
                    preCropUv += warp;
                }
                vec2 delta = preCropUv - vec2(0.5);
                float aspect = max(uSrcAspect, 0.0001);
                vec2 physDelta = vec2(delta.x * aspect, delta.y);
                float maxCornerR2 = 0.25 * (aspect * aspect + 1.0);
                float radiusSquared = dot(physDelta, physDelta) / maxCornerR2;

                vec4 sampled;
                if (abs(uCaRedK) < 0.000001 && abs(uCaBlueK) < 0.000001) {
                    sampled = texture(uTex0, preCropUv);
                } else {
                    vec2 redUv = vec2(0.5) + delta * (1.0 + uCaRedK * radiusSquared);
                    vec2 blueUv = vec2(0.5) + delta * (1.0 + uCaBlueK * radiusSquared);
                    vec4 greenReference = texture(uTex0, preCropUv);
                    float red = texture(uTex0, redUv).r;
                    float blue = texture(uTex0, blueUv).b;
                    sampled = vec4(red, greenReference.g, blue, greenReference.a);
                }

                if (uEncodeSrgbWithDither == 1) {
                    vec3 srgb = encodeSrgb(max(sampled.rgb, vec3(0.0)));
                    float dither = triNoise(gl_FragCoord.xy) / 255.0;
                    fragColor = vec4(clamp(srgb + vec3(dither), 0.0, 1.0), sampled.a);
                } else {
                    fragColor = sampled;
                }
            }
        """.trimIndent()
    }
}
