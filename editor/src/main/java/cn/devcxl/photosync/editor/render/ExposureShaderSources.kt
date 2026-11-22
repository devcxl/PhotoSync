package cn.devcxl.photosync.editor.render

/**
 * GLSL ES sources for [ExposureGlProgram].
 *
 * The ES 3.00 and ES 1.00 variants implement identical math: sRGB transfer functions, the broadband
 * `camera_default` base look, Pass B1 (`ToneShaderMath`), and Pass B2 (`PassBColorMath` via
 * [ColorShaderGlsl]). Keep both CPU references and GLSL in lock-step when editing either side.
 */
internal object ExposureShaderSources {

    val VERTEX_SHADER_ES3 = """
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

    val VERTEX_SHADER_ES2 = """
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

    /** Scalar + vector sRGB transfer functions, valid in both GLSL ES 1.00 and 3.00. */
    private val SRGB_GLSL = """
        float decodeSrgbScalar(float v) {
            float x = max(v, 0.0);
            return (x <= 0.04045) ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4);
        }

        vec3 decodeSrgb(vec3 c) {
            vec3 lo = max(c, vec3(0.0)) / 12.92;
            vec3 hi = pow((max(c, vec3(0.0)) + 0.055) / 1.055, vec3(2.4));
            return mix(lo, hi, step(vec3(0.04045), c));
        }

        float encodeSrgbScalar(float v) {
            float x = clamp(v, 0.0, 1.0);
            return (x <= 0.0031308) ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
        }

        vec3 encodeSrgb(vec3 c) {
            vec3 v = clamp(c, 0.0, 1.0);
            vec3 lo = v * 12.92;
            vec3 hi = 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055;
            return mix(lo, hi, step(vec3(0.0031308), v));
        }
    """.trimIndent()

    /** Browse-page tone curve and vibrance; mirrors `CameraDefaultLookMath` exactly. */
    private val CAMERA_DEFAULT_GLSL = """
        vec3 cameraDefaultLook(vec3 srgb) {
            vec3 lifted = pow(clamp(srgb, 0.0, 1.0), vec3(0.78));
            vec3 sCurve = lifted * lifted * (3.0 - 2.0 * lifted);
            vec3 toned = mix(lifted, sCurve, 0.12);
            float luma = dot(toned, vec3(0.2126, 0.7152, 0.0722));
            float fade = clamp((luma * 255.0 - 12.0) / 48.0, 0.0, 1.0);
            float saturation =
                max(max(toned.r, toned.g), toned.b) - min(min(toned.r, toned.g), toned.b);
            float scale = 1.0 + 0.18 * fade * max(0.0, 1.0 - saturation);
            return clamp(luma + (toned - luma) * scale, 0.0, 1.0);
        }

        vec3 applyBaseLook(vec3 linearRgb) {
            if (uBaseLook == 1) {
                vec3 base = decodeSrgb(cameraDefaultLook(encodeSrgb(min(linearRgb, vec3(1.0)))));
                vec3 headroom = max(linearRgb - vec3(1.0), vec3(0.0));
                return base + headroom;
            }
            return linearRgb;
        }
    """.trimIndent()

    /**
     * Pass B upper half. Mirrors `ToneShaderMath` stage for stage; the constants must not drift.
     */
    private val TONE_GLSL = """
        const vec3 LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);

        float luminance(vec3 c) {
            return dot(c, LUMA_WEIGHTS);
        }

        float softShoulder(float x) {
            float t0 = 0.85;
            float span = 0.15;
            if (x <= t0) {
                return max(x, 0.0);
            }
            return t0 + span * softTanh((x - t0) / span);
        }

        float smoothStep01(float y) {
            return y * y * (3.0 - 2.0 * y);
        }

        float brightnessWeight(float y) {
            float ratio = log2(max(y, 1e-5) / 0.18) / 1.1;
            return exp(-0.5 * ratio * ratio);
        }

        // Linear stage: white balance -> exposure -> middle-gray brightness -> highlight trims.
        // Every trim runs before the [0, 1] clip so negative values still see Y > 1 headroom.
        vec3 toneLinear(vec3 c) {
            if (uHasWb == 1) {
                c = uWb * c;
            }
            c = max(c, vec3(0.0)) * exp2(uExposureEv);

            float y = luminance(c);
            c *= exp2(uBrightness * brightnessWeight(y));

            y = luminance(c);
            float encoded = encodeSrgbScalar(clamp(y, 0.0, 1.0));
            float wHi = smoothstep(0.5, 1.0, encoded);
            float wSh = 1.0 - smoothstep(0.0, 0.5, encoded);
            float wWhites = smoothstep(0.2, 1.0, encoded);
            c *= exp2(uHighlights * wHi + uShadows * wSh + 0.5 * uWhites * wWhites);
            return c;
        }

        // Display stage: shoulder, sRGB encode, blacks toe, piecewise contrast, glow stages 1-2.
        float displayLuminance(float linearLuminance) {
            float y = encodeSrgbScalar(softShoulder(linearLuminance));
            y = y + 0.18 * uBlacks * y * (1.0 - y) * (1.0 - y);
            if (uBlacks < 0.0) {
                y = max(0.0, (y + 0.04 * uBlacks) / (1.0 + 0.04 * uBlacks));
            }
            y = clamp(y, 0.0, 1.0);
            y = (uContrast >= 0.0)
                ? mix(y, smoothStep01(y), uContrast)
                : mix(y, 0.5 + 0.5 * (y - 0.5), -uContrast);
            float base = clamp(y, 0.0, 1.0);
            float glow = pow(base, 1.0 / (1.0 + 0.20 * uGlow))
                + 0.10 * uGlow * base * (1.0 - base) * (1.0 - base);
            return clamp(glow, 0.0, 1.0);
        }

        // Hue-preserving recombine: scale by the new luminance, then compress over-range channels
        // towards the luminance so a lifted saturated shadow desaturates instead of hue-shifting.
        vec3 toneDisplay(vec3 linearRgb) {
            float y = max(luminance(linearRgb), 0.0);
            float display = displayLuminance(y);
            vec3 scaled = linearRgb * (decodeSrgbScalar(display) / max(y, 1e-6));
            float maxChannel = max(max(scaled.r, scaled.g), scaled.b);
            if (maxChannel > 1.0) {
                float compressed = luminance(scaled);
                scaled = compressed + (scaled - compressed) / maxChannel;
            }
            return encodeSrgb(scaled);
        }
    """.trimIndent()

    private val COMMON_UNIFORMS = """
        uniform sampler2D uTex0;
        uniform int uCompareOriginal;
        uniform int uIsLinearInput;
        uniform int uBaseLook;
        uniform float uAutoBrightGain;
        uniform int uToneActive;
        uniform int uHasWb;
        uniform mat3 uWb;
        uniform float uExposureEv;
        uniform float uBrightness;
        uniform float uContrast;
        uniform float uGlow;
        uniform float uHighlights;
        uniform float uShadows;
        uniform float uWhites;
        uniform float uBlacks;
        ${ColorShaderGlsl.UNIFORMS}
    """.trimIndent()

    val FRAGMENT_SHADER_ES3 = """
        #version 300 es
        precision highp float;
        in vec2 vTexCoord;
        out vec4 fragColor;
        $COMMON_UNIFORMS

        float softTanh(float x) {
            return tanh(x);
        }

        vec4 sampleTex2D(sampler2D s, vec2 uv) {
            return texture(s, uv);
        }

        $SRGB_GLSL
        $CAMERA_DEFAULT_GLSL
        $TONE_GLSL
        ${ColorShaderGlsl.FUNCTIONS}

        void main() {
            vec4 src = texture(uTex0, vTexCoord);
            vec3 linear = (uIsLinearInput == 1)
                ? applyBaseLook(max(src.rgb, vec3(0.0)) * uAutoBrightGain)
                : decodeSrgb(src.rgb);
            if (uCompareOriginal == 1 || (uToneActive == 0 && uPassB2Active == 0)) {
                fragColor = vec4(encodeSrgb(linear), src.a);
                return;
            }
            vec3 srgb = (uToneActive == 1) ? toneDisplay(toneLinear(linear)) : encodeSrgb(linear);
            if (uPassB2Active == 1) {
                srgb = applyColorGrading(srgb);
            }
            fragColor = vec4(srgb, src.a);
        }
    """.trimIndent()

    val FRAGMENT_SHADER_ES2 = """
        precision highp float;
        varying vec2 vTexCoord;
        $COMMON_UNIFORMS

        float softTanh(float x) {
            float e2 = exp(clamp(2.0 * x, -20.0, 20.0));
            return (e2 - 1.0) / (e2 + 1.0);
        }

        vec4 sampleTex2D(sampler2D s, vec2 uv) {
            return texture2D(s, uv);
        }

        $SRGB_GLSL
        $CAMERA_DEFAULT_GLSL
        $TONE_GLSL
        ${ColorShaderGlsl.FUNCTIONS}

        void main() {
            vec4 src = texture2D(uTex0, vTexCoord);
            vec3 linear = (uIsLinearInput == 1)
                ? applyBaseLook(max(src.rgb, vec3(0.0)) * uAutoBrightGain)
                : decodeSrgb(src.rgb);
            if (uCompareOriginal == 1 || (uToneActive == 0 && uPassB2Active == 0)) {
                gl_FragColor = vec4(encodeSrgb(linear), src.a);
                return;
            }
            vec3 srgb = (uToneActive == 1) ? toneDisplay(toneLinear(linear)) : encodeSrgb(linear);
            if (uPassB2Active == 1) {
                srgb = applyColorGrading(srgb);
            }
            gl_FragColor = vec4(srgb, src.a);
        }
    """.trimIndent()

    /** Tier 3 minimal fallback shader (6 uniforms): BaseLook + Exposure only when ES 2.0 uniform limits fail. */
    val FRAGMENT_SHADER_MINIMAL_ES2 = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTex0;
        uniform int uCompareOriginal;
        uniform int uIsLinearInput;
        uniform int uBaseLook;
        uniform float uAutoBrightGain;
        uniform float uExposureEv;

        $SRGB_GLSL
        $CAMERA_DEFAULT_GLSL

        void main() {
            vec4 src = texture2D(uTex0, vTexCoord);
            vec3 linear = (uIsLinearInput == 1)
                ? applyBaseLook(max(src.rgb, vec3(0.0)) * uAutoBrightGain)
                : decodeSrgb(src.rgb);
            if (uCompareOriginal == 1) {
                gl_FragColor = vec4(encodeSrgb(linear), src.a);
                return;
            }
            vec3 exposed = linear * exp2(uExposureEv);
            gl_FragColor = vec4(encodeSrgb(clamp(exposed, 0.0, 1.0)), src.a);
        }
    """.trimIndent()
}
