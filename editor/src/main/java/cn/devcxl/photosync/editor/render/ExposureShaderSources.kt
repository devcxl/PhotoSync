package cn.devcxl.photosync.editor.render

/**
 * GLSL ES sources for [ExposureGlProgram].
 *
 * The ES 3.00 and ES 1.00 variants intentionally implement identical math: sRGB transfer
 * functions, the linear soft shoulder, and the `camera_default` base look transcribed from
 * `libraw/src/main/cpp/raw_postprocess.cpp`.
 */
internal object ExposureShaderSources {

    val VERTEX_SHADER_ES3 = """
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

    val BASE_LOOK_GLSL = """
        vec3 decodeSrgb(vec3 c) {
            vec3 lo = c / 12.92;
            vec3 hi = pow((max(c, vec3(0.0)) + 0.055) / 1.055, vec3(2.4));
            return mix(lo, hi, step(vec3(0.04045), c));
        }

        vec3 encodeSrgb(vec3 c) {
            vec3 v = clamp(c, 0.0, 1.0);
            vec3 lo = v * 12.92;
            vec3 hi = 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055;
            return mix(lo, hi, step(vec3(0.0031308), v));
        }

        vec3 softShoulder(vec3 c) {
            float t0 = 0.85;
            float span = 0.15;
            vec3 over = max(c - vec3(t0), vec3(0.0)) / span;
            vec3 rolled = vec3(t0) + span * tanh(over);
            return mix(c, rolled, step(vec3(t0), c));
        }

        // Mirrors CameraDefaultLookMath and raw_postprocess.cpp exactly.
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
    """.trimIndent()

    val VERTEX_SHADER_ES2 = """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        uniform vec2 uScale;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    val FRAGMENT_SHADER_ES3 = """
        #version 300 es
        precision highp float;
        in vec2 vTexCoord;
        uniform sampler2D uTex0;
        uniform float uExposureEv;
        uniform int uCompareOriginal;
        uniform int uIsLinearInput;
        uniform int uBaseLook;
        uniform float uAutoBrightGain;
        out vec4 fragColor;

        $BASE_LOOK_GLSL

        vec3 renderLinear(vec3 lin, bool applyExposure) {
            if (applyExposure) {
                if (uBaseLook == 1) {
                    lin = decodeSrgb(cameraDefaultLook(encodeSrgb(lin)));
                }
                return encodeSrgb(softShoulder(max(lin, vec3(0.0)) * exp2(uExposureEv)));
            }
            if (uBaseLook == 1) {
                return cameraDefaultLook(encodeSrgb(lin));
            }
            return encodeSrgb(lin);
        }

        void main() {
            vec4 src = texture(uTex0, vTexCoord);
            if (uIsLinearInput == 1) {
                vec3 lin = max(src.rgb, vec3(0.0)) * uAutoBrightGain;
                bool applyExposure = uCompareOriginal == 0 && abs(uExposureEv) >= 0.0001;
                fragColor = vec4(renderLinear(lin, applyExposure), src.a);
                return;
            }
            if (uCompareOriginal == 0 && abs(uExposureEv) >= 0.0001) {
                vec3 lin = decodeSrgb(src.rgb) * exp2(uExposureEv);
                fragColor = vec4(encodeSrgb(softShoulder(max(lin, vec3(0.0)))), src.a);
                return;
            }
            fragColor = src;
        }
    """.trimIndent()

    val FRAGMENT_SHADER_ES2 = """
        precision highp float;
        varying vec2 vTexCoord;
        uniform sampler2D uTex0;
        uniform float uExposureEv;
        uniform int uCompareOriginal;
        uniform int uIsLinearInput;
        uniform int uBaseLook;
        uniform float uAutoBrightGain;

        vec3 tanhApprox(vec3 x) {
            vec3 e2 = exp(clamp(2.0 * x, vec3(-20.0), vec3(20.0)));
            return (e2 - vec3(1.0)) / (e2 + vec3(1.0));
        }

        vec3 decodeSrgb(vec3 c) {
            vec3 lo = c / 12.92;
            vec3 hi = pow((max(c, vec3(0.0)) + 0.055) / 1.055, vec3(2.4));
            return mix(lo, hi, step(vec3(0.04045), c));
        }

        vec3 encodeSrgb(vec3 c) {
            vec3 v = clamp(c, 0.0, 1.0);
            vec3 lo = v * 12.92;
            vec3 hi = 1.055 * pow(v, vec3(1.0 / 2.4)) - 0.055;
            return mix(lo, hi, step(vec3(0.0031308), v));
        }

        vec3 softShoulder(vec3 c) {
            float t0 = 0.85;
            float span = 0.15;
            vec3 over = max(c - vec3(t0), vec3(0.0)) / span;
            vec3 rolled = vec3(t0) + span * tanhApprox(over);
            return mix(c, rolled, step(vec3(t0), c));
        }

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

        vec3 renderLinear(vec3 lin, bool applyExposure) {
            if (applyExposure) {
                if (uBaseLook == 1) {
                    lin = decodeSrgb(cameraDefaultLook(encodeSrgb(lin)));
                }
                return encodeSrgb(softShoulder(max(lin, vec3(0.0)) * exp2(uExposureEv)));
            }
            if (uBaseLook == 1) {
                return cameraDefaultLook(encodeSrgb(lin));
            }
            return encodeSrgb(lin);
        }

        void main() {
            vec4 src = texture2D(uTex0, vTexCoord);
            if (uIsLinearInput == 1) {
                vec3 lin = max(src.rgb, vec3(0.0)) * uAutoBrightGain;
                bool applyExposure = uCompareOriginal == 0 && abs(uExposureEv) >= 0.0001;
                gl_FragColor = vec4(renderLinear(lin, applyExposure), src.a);
                return;
            }
            if (uCompareOriginal == 0 && abs(uExposureEv) >= 0.0001) {
                vec3 lin = decodeSrgb(src.rgb) * exp2(uExposureEv);
                gl_FragColor = vec4(encodeSrgb(softShoulder(max(lin, vec3(0.0)))), src.a);
                return;
            }
            gl_FragColor = src;
        }
    """.trimIndent()
}
