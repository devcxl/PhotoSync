package cn.devcxl.photosync.editor.render

/**
 * Shared GLSL ES 1.00 / 3.00 fragment snippet for the Pass B lower half (`PassBColorMath` parity):
 * 1024-entry curve LUT (RGB composite then per-channel), soft gamut compression, linear sRGB <->
 * OKLab/OKLCH, Saturation, Vibrance (high-chroma clamp + shadow attenuation + 45° skin protect),
 * 8-band OKLCH HSL with `smoothstep(0.01, 0.05, C)` gate, Cartesian OKLab split toning, edge
 * defringe and film fade.
 *
 * Uses `sampleTex2D` provided by the enclosing ES 3.00 / ES 1.00 shader header and constant-index
 * array reads for full ES 2.0 compatibility.
 */
internal object ColorShaderGlsl {

    val UNIFORMS = """
        uniform sampler2D uCurveLut;
        uniform vec2 uTexelSize;
        uniform int uPassB2Active;
        uniform int uColorActive;
        uniform int uCurveActive;
        uniform float uSaturation;
        uniform float uVibrance;
        uniform float uFade;
        uniform float uDefringe;
        uniform int uHslActive;
        uniform vec3 uHslBands[8];
        uniform int uSplitActive;
        uniform vec2 uSplitHiAb;
        uniform vec2 uSplitShAb;
        uniform float uSplitBalance;
    """.trimIndent()

    val FUNCTIONS = """
        const float PI_CONST = 3.14159265;
        const float TWO_PI_CONST = 6.28318531;

        float signedCbrt(float v) {
            if (abs(v) < 1e-12) return 0.0;
            float mag = pow(abs(v), 1.0 / 3.0);
            return (v < 0.0) ? -mag : mag;
        }

        float shortestAngle(float a, float b) {
            float d = mod(a - b + PI_CONST, TWO_PI_CONST) - PI_CONST;
            return (d < -PI_CONST) ? (d + TWO_PI_CONST) : d;
        }

        vec3 softGamutCompress(vec3 c) {
            float maxCh = max(max(c.r, c.g), c.b);
            if (maxCh <= 1.0) return c;
            float luma = luminance(c);
            return luma + (c - luma) / maxCh;
        }

        vec3 linearSrgbToOklab(vec3 c) {
            float l = 0.41222147 * c.r + 0.53633254 * c.g + 0.05144599 * c.b;
            float m = 0.21190350 * c.r + 0.68069955 * c.g + 0.10739696 * c.b;
            float s = 0.08830246 * c.r + 0.28171884 * c.g + 0.62997870 * c.b;
            float l_ = signedCbrt(l);
            float m_ = signedCbrt(m);
            float s_ = signedCbrt(s);
            return vec3(
                0.21045426 * l_ + 0.79361779 * m_ - 0.00407205 * s_,
                1.97799850 * l_ - 2.42859221 * m_ + 0.45059371 * s_,
                0.02590404 * l_ + 0.78277177 * m_ - 0.80867577 * s_
            );
        }

        vec3 oklabToLinearSrgb(vec3 lab) {
            float l_ = lab.x + 0.39633778 * lab.y + 0.21580376 * lab.z;
            float m_ = lab.x - 0.10556135 * lab.y - 0.06385417 * lab.z;
            float s_ = lab.x - 0.08948418 * lab.y - 1.29148555 * lab.z;
            float l = l_ * l_ * l_;
            float m = m_ * m_ * m_;
            float s = s_ * s_ * s_;
            return vec3(
                4.07674166 * l - 3.30771159 * m + 0.23096993 * s,
                -1.26843800 * l + 2.60975740 * m - 0.34131940 * s,
                -0.00419609 * l - 0.70341861 * m + 1.70761470 * s
            );
        }

        vec3 oklabToOklch(vec3 lab) {
            float C = length(lab.yz);
            float h = (C > 1e-6) ? atan(lab.z, lab.y) : 0.0;
            return vec3(lab.x, C, h);
        }

        vec3 oklchToOklab(vec3 lch) {
            return vec3(lch.x, lch.y * cos(lch.z), lch.y * sin(lch.z));
        }

        float skinProtect(float hueRad) {
            float diff = shortestAngle(hueRad, 0.78539816) / 0.43633231;
            return 1.0 - 0.70 * exp(-0.5 * diff * diff);
        }

        float curveCoord(float v) {
            return clamp(v, 0.0, 1.0) * (1023.0 / 1024.0) + (0.5 / 1024.0);
        }

        vec3 applyCurveLut(vec3 srgb) {
            float cR = sampleTex2D(uCurveLut, vec2(curveCoord(srgb.r), 0.5)).a;
            float cG = sampleTex2D(uCurveLut, vec2(curveCoord(srgb.g), 0.5)).a;
            float cB = sampleTex2D(uCurveLut, vec2(curveCoord(srgb.b), 0.5)).a;
            return vec3(
                sampleTex2D(uCurveLut, vec2(curveCoord(cR), 0.5)).r,
                sampleTex2D(uCurveLut, vec2(curveCoord(cG), 0.5)).g,
                sampleTex2D(uCurveLut, vec2(curveCoord(cB), 0.5)).b
            );
        }

        void accumHslBand(vec3 band, float centerDeg, float hueRad, inout float wSum, inout vec3 dSum) {
            float diff = shortestAngle(hueRad, radians(centerDeg)) / 0.45378561;
            float w = exp(-0.5 * diff * diff);
            wSum += w;
            dSum += w * band;
        }

        vec3 applyHslBands(vec3 oklch) {
            float chromaGate = smoothstep(0.01, 0.05, oklch.y);
            float wSum = 0.0;
            vec3 dSum = vec3(0.0);
            accumHslBand(uHslBands[0], 29.0, oklch.z, wSum, dSum);
            accumHslBand(uHslBands[1], 53.0, oklch.z, wSum, dSum);
            accumHslBand(uHslBands[2], 110.0, oklch.z, wSum, dSum);
            accumHslBand(uHslBands[3], 142.0, oklch.z, wSum, dSum);
            accumHslBand(uHslBands[4], 195.0, oklch.z, wSum, dSum);
            accumHslBand(uHslBands[5], 264.0, oklch.z, wSum, dSum);
            accumHslBand(uHslBands[6], 294.0, oklch.z, wSum, dSum);
            accumHslBand(uHslBands[7], 328.0, oklch.z, wSum, dSum);
            float invW = 1.0 / max(wSum, 1.0);
            vec3 delta = dSum * invW;
            oklch.z += delta.x * 0.52359878 * chromaGate;
            oklch.y *= max(0.0, 1.0 + delta.y);
            oklch.x = clamp(oklch.x + delta.z * 0.25 * chromaGate, 0.0, 1.0);
            return oklch;
        }

        float sampleBaseSrgbLuma(vec2 uv) {
            vec3 raw = sampleTex2D(uTex0, uv).rgb;
            vec3 srgb = (uIsLinearInput == 1)
                ? encodeSrgb(clamp(applyBaseLook(max(raw, vec3(0.0)) * uAutoBrightGain), 0.0, 1.0))
                : raw;
            return luminance(srgb);
        }

        float computeEdgeGradient() {
            if (uTexelSize.x <= 0.0 || uTexelSize.y <= 0.0) return 0.0;
            float lL = sampleBaseSrgbLuma(vTexCoord - vec2(uTexelSize.x, 0.0));
            float lR = sampleBaseSrgbLuma(vTexCoord + vec2(uTexelSize.x, 0.0));
            float lU = sampleBaseSrgbLuma(vTexCoord - vec2(0.0, uTexelSize.y));
            float lD = sampleBaseSrgbLuma(vTexCoord + vec2(0.0, uTexelSize.y));
            return max(abs(lR - lL), abs(lD - lU));
        }

        vec3 applyColorGrading(vec3 srgb) {
            vec3 working = clamp(srgb, 0.0, 1.0);
            if (uCurveActive == 1) {
                working = applyCurveLut(working);
            }
            if (uColorActive == 1) {
                vec3 oklch = oklabToOklch(linearSrgbToOklab(decodeSrgb(working)));
                oklch.y *= max(0.0, 1.0 + uSaturation);
                if (abs(uVibrance) > 1e-6) {
                    float wSat = clamp(1.0 - oklch.y / 0.32, 0.0, 1.0);
                    float wShadow = smoothstep(0.05, 0.20, oklch.x);
                    oklch.y *= max(0.0, 1.0 + uVibrance * wSat * wShadow * skinProtect(oklch.z));
                }
                if (uHslActive == 1) {
                    oklch = applyHslBands(oklch);
                }
                if (uDefringe > 0.0) {
                    float grad = computeEdgeGradient();
                    float edgeWeight = smoothstep(0.04, 0.20, grad);
                    float chromaGate = smoothstep(0.03, 0.12, oklch.y);
                    float pDiff = shortestAngle(oklch.z, 5.49778714) / 0.31415927;
                    float gDiff = shortestAngle(oklch.z, 2.47836754) / 0.27925268;
                    float fringeHue = max(exp(-0.5 * pDiff * pDiff), exp(-0.5 * gDiff * gDiff));
                    float amount = clamp(uDefringe * edgeWeight * chromaGate * fringeHue, 0.0, 1.0);
                    oklch.y *= (1.0 - amount * 0.85);
                }
                vec3 oklab = oklchToOklab(oklch);
                if (uSplitActive == 1) {
                    float L = clamp(oklab.x, 0.0, 1.0);
                    float shiftedL = clamp(L + 0.35 * uSplitBalance * (1.0 - abs(2.0 * L - 1.0)), 0.0, 1.0);
                    float wHi = smoothstep(0.35, 0.85, shiftedL);
                    float wSh = 1.0 - smoothstep(0.15, 0.65, shiftedL);
                    oklab.yz += wHi * uSplitHiAb + wSh * uSplitShAb;
                }
                vec3 gradedLin = softGamutCompress(oklabToLinearSrgb(oklab));
                working = encodeSrgb(clamp(gradedLin, 0.0, 1.0));
            }
            if (uFade > 0.0) {
                working = working * (1.0 - 0.15 * uFade) + 0.12 * uFade;
                float fLuma = luminance(working);
                working = clamp(mix(working, vec3(fLuma), 0.18 * uFade), 0.0, 1.0);
            }
            return working;
        }
    """.trimIndent()
}
