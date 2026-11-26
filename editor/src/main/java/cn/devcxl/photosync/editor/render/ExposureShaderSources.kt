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

    /** Post-Pass A cloud layer inputs; every value stays neutral when no layer is active. */
    private val CLOUD_UNIFORMS = """
        uniform sampler2D uCloudLayerTex;
        uniform sampler2D uCloudMaskTex;
        uniform int uCloudActive;
        uniform int uCloudHasMask;
        uniform float uCloudOpacity;
        uniform vec2 uCloudUvScale;
        uniform vec2 uCloudUvOffset;
    """.trimIndent()

    private val COMMON_UNIFORMS = """
        uniform sampler2D uTex0;
        $CLOUD_UNIFORMS
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
        uniform float uDitherAmp;
        ${ColorShaderGlsl.UNIFORMS}
        ${ColorShaderGlsl.SUBJECT_UNIFORMS}
    """.trimIndent()

    /** `1/255` triangular output dither shared by both GLSL dialects. */
    private val DITHER_GLSL = """
        float triDither(vec2 position) {
            float n1 = fract(sin(dot(position, vec2(12.9898, 78.233))) * 43758.5453);
            float n2 = fract(sin(dot(position + vec2(1.0, 1.0), vec2(39.346, 11.135))) * 43758.5453);
            return (n1 - n2) * uDitherAmp;
        }
    """.trimIndent()

    /**
     * Post-Pass A / Pre-Pass B cloud layer fusion.
     *
     * The layer is sampled in the frame grid of the texture Pass B reads: the full post-crop frame
     * for the preview canvas, and `uCloudUvScale/Offset`-mapped band UV inside a bake band. The
     * blend runs before `BaseLook`, white balance, tone and display colour, so a cloud result is
     * graded by exactly the same local pipeline as the camera pixels around it.
     *
     * A layer PNG is display-referred sRGB; a linear Pass A target therefore decodes it first, which
     * keeps the blend in the same encoding the rest of the shader works in.
     */
    private val CLOUD_GLSL = """
        vec3 blendCloudLayer(vec3 base) {
            if (uCloudActive == 0) {
                return base;
            }
            vec2 uv = vTexCoord * uCloudUvScale + uCloudUvOffset;
            vec4 cloud = sampleTex2D(uCloudLayerTex, uv);
            float coverage = uCloudOpacity;
            if (uCloudHasMask == 1) {
                coverage *= sampleTex2D(uCloudMaskTex, uv).r;
            }
            coverage *= cloud.a;
            vec3 cloudRgb = (uIsLinearInput == 1) ? decodeSrgb(cloud.rgb) : cloud.rgb;
            return mix(base, cloudRgb, clamp(coverage, 0.0, 1.0));
        }
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

        vec4 sampleSubjectTex(sampler2D s, vec2 uv) {
            return texture(s, uv);
        }

        $DITHER_GLSL
        $SRGB_GLSL
        $CLOUD_GLSL
        $CAMERA_DEFAULT_GLSL
        $TONE_GLSL
        ${ColorShaderGlsl.FUNCTIONS}
        ${ColorShaderGlsl.SUBJECT_RELIGHT_FUNCTIONS}

        void main() {
            vec4 src = texture(uTex0, vTexCoord);
            if (uCompareOriginal == 0) {
                src.rgb = blendCloudLayer(src.rgb);
            }
            vec3 linear = (uIsLinearInput == 1)
                ? applyBaseLook(max(src.rgb, vec3(0.0)) * uAutoBrightGain)
                : decodeSrgb(src.rgb);
            if (uCompareOriginal == 1) {
                vec3 passthrough = encodeSrgb(linear) + vec3(triDither(gl_FragCoord.xy));
                fragColor = vec4(clamp(passthrough, 0.0, 1.0), src.a);
                return;
            }
            linear = applySubjectRelight(linear);
            if (uToneActive == 0 && uPassB2Active == 0) {
                vec3 passthrough = encodeSrgb(linear) + vec3(triDither(gl_FragCoord.xy));
                fragColor = vec4(clamp(passthrough, 0.0, 1.0), src.a);
                return;
            }
            vec3 srgb = (uToneActive == 1) ? toneDisplay(toneLinear(linear)) : encodeSrgb(linear);
            if (uPassB2Active == 1) {
                srgb = applyColorGrading(srgb);
            }
            fragColor = vec4(clamp(srgb + vec3(triDither(gl_FragCoord.xy)), 0.0, 1.0), src.a);
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

        vec4 sampleSubjectTex(sampler2D s, vec2 uv) {
            return texture2D(s, uv);
        }

        $DITHER_GLSL
        $SRGB_GLSL
        $CLOUD_GLSL
        $CAMERA_DEFAULT_GLSL
        $TONE_GLSL
        ${ColorShaderGlsl.FUNCTIONS}
        ${ColorShaderGlsl.SUBJECT_RELIGHT_FUNCTIONS}

        void main() {
            vec4 src = texture2D(uTex0, vTexCoord);
            if (uCompareOriginal == 0) {
                src.rgb = blendCloudLayer(src.rgb);
            }
            vec3 linear = (uIsLinearInput == 1)
                ? applyBaseLook(max(src.rgb, vec3(0.0)) * uAutoBrightGain)
                : decodeSrgb(src.rgb);
            if (uCompareOriginal == 1) {
                vec3 passthrough = encodeSrgb(linear) + vec3(triDither(gl_FragCoord.xy));
                gl_FragColor = vec4(clamp(passthrough, 0.0, 1.0), src.a);
                return;
            }
            linear = applySubjectRelight(linear);
            if (uToneActive == 0 && uPassB2Active == 0) {
                vec3 passthrough = encodeSrgb(linear) + vec3(triDither(gl_FragCoord.xy));
                gl_FragColor = vec4(clamp(passthrough, 0.0, 1.0), src.a);
                return;
            }
            vec3 srgb = (uToneActive == 1) ? toneDisplay(toneLinear(linear)) : encodeSrgb(linear);
            if (uPassB2Active == 1) {
                srgb = applyColorGrading(srgb);
            }
            gl_FragColor = vec4(clamp(srgb + vec3(triDither(gl_FragCoord.xy)), 0.0, 1.0), src.a);
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
