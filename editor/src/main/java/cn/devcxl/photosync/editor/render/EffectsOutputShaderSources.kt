package cn.devcxl.photosync.editor.render

/**
 * GLSL ES 3.00 source of Pass D (`EffectsOutputPass`): subject cut-out composite, tetrahedral 3D LUT,
 * post-crop film grain, post-crop vignette and `1/255` triangular dithering.
 *
 * Every formula mirrors `PassDEffectsMath` constant for constant, and the vertex stage is shared
 * with Pass B so both passes use the same canvas letterbox transform.
 */
internal object EffectsOutputShaderSources {

    val FRAGMENT_SHADER_ES3 = """
        #version 300 es
        precision highp float;
        precision highp sampler3D;
        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform sampler2D uTex0;
        uniform sampler3D uLutTex;
        uniform int uCompareOriginal;
        uniform int uLutActive;
        uniform float uLutStrength;
        uniform float uLutSize;
        uniform vec4 uGrain;
        uniform vec4 uVignette;
        uniform float uAspect;
        uniform float uDitherAmp;
        uniform vec4 uTileUvTransform;
        uniform vec2 uTilePixelScale;
        uniform int uSubjectMode;
        uniform sampler2D uSubjectTex;
        uniform vec4 uSubjectColor;
        uniform mat3 uSubjectUvMatrix;
        uniform int uSubjectLocal;
        uniform sampler2D uSubjectWarpTex;
        uniform int uHasSubjectWarpTex;
        uniform vec2 uSubjectWarpScale;
        uniform vec2 uSubjectWarpOffset;
        uniform int uSubjectChecker;

        const vec3 LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);
        const float GRAIN_FINE_FREQUENCY = 1400.0;
        const float GRAIN_COARSE_FREQUENCY = 120.0;
        const float GRAIN_MAX_AMPLITUDE = 0.06;

        float hash21(vec2 cell, float seed) {
            vec3 p = fract(vec3(cell.xyx) * 0.1031 + seed * 0.0173);
            p += dot(p, p.yzx + 33.33);
            return fract((p.x + p.y) * p.z);
        }

        float valueNoise(vec2 uv, float frequency, float seed) {
            vec2 position = uv * frequency;
            vec2 cell = floor(position);
            vec2 f = position - cell;
            vec2 w = f * f * (3.0 - 2.0 * f);
            float n00 = hash21(cell, seed);
            float n10 = hash21(cell + vec2(1.0, 0.0), seed);
            float n01 = hash21(cell + vec2(0.0, 1.0), seed);
            float n11 = hash21(cell + vec2(1.0, 1.0), seed);
            return mix(mix(n00, n10, w.x), mix(n01, n11, w.x), w.y);
        }

        vec3 tetrahedralLut(vec3 color) {
            float steps = uLutSize - 1.0;
            vec3 position = clamp(color, 0.0, 1.0) * steps;
            // The base cell must stop at size - 2 so texelFetch(i + 1) stays inside the cube.
            ivec3 cell = ivec3(clamp(floor(position), vec3(0.0), vec3(uLutSize - 2.0)));
            vec3 f = position - vec3(cell);
            vec3 c000 = texelFetch(uLutTex, cell + ivec3(0, 0, 0), 0).rgb;
            vec3 c100 = texelFetch(uLutTex, cell + ivec3(1, 0, 0), 0).rgb;
            vec3 c010 = texelFetch(uLutTex, cell + ivec3(0, 1, 0), 0).rgb;
            vec3 c001 = texelFetch(uLutTex, cell + ivec3(0, 0, 1), 0).rgb;
            vec3 c110 = texelFetch(uLutTex, cell + ivec3(1, 1, 0), 0).rgb;
            vec3 c101 = texelFetch(uLutTex, cell + ivec3(1, 0, 1), 0).rgb;
            vec3 c011 = texelFetch(uLutTex, cell + ivec3(0, 1, 1), 0).rgb;
            vec3 c111 = texelFetch(uLutTex, cell + ivec3(1, 1, 1), 0).rgb;
            if (f.r > f.g) {
                if (f.g > f.b) {
                    return c000 + f.r * (c100 - c000) + f.g * (c110 - c100) + f.b * (c111 - c110);
                } else if (f.r > f.b) {
                    return c000 + f.r * (c100 - c000) + f.b * (c101 - c100) + f.g * (c111 - c101);
                }
                return c000 + f.b * (c001 - c000) + f.r * (c101 - c001) + f.g * (c111 - c101);
            }
            if (f.r > f.b) {
                return c000 + f.g * (c010 - c000) + f.r * (c110 - c010) + f.b * (c111 - c110);
            } else if (f.g > f.b) {
                return c000 + f.g * (c010 - c000) + f.b * (c011 - c010) + f.r * (c111 - c011);
            }
            return c000 + f.b * (c001 - c000) + f.g * (c011 - c001) + f.r * (c111 - c011);
        }

        float grainOffset(vec2 uv, float luminance) {
            float frequency = mix(GRAIN_FINE_FREQUENCY, GRAIN_COARSE_FREQUENCY, uGrain.y);
            float coarse = valueNoise(uv, frequency, uGrain.w);
            float octave = valueNoise(uv * 2.0 + vec2(37.0, 11.0), frequency, uGrain.w + 7.0);
            float layered = mix(coarse, (coarse + 0.5 * octave) / 1.5, uGrain.z);
            float envelope = sqrt(clamp(4.0 * luminance * (1.0 - luminance), 0.0, 1.0));
            return (layered - 0.5) * 2.0 * uGrain.x * GRAIN_MAX_AMPLITUDE * envelope;
        }

        float vignetteGain(vec2 uv) {
            vec2 rel = (uv - 0.5) * 2.0;
            vec2 phys = vec2(rel.x * uAspect, rel.y);
            float cornerRadius = sqrt(uAspect * uAspect + 1.0);
            float ellipse = length(phys) / cornerRadius;
            float rectangle = max(abs(phys.x), abs(phys.y)) / max(uAspect, 1.0);
            float circle = length(rel) / sqrt(2.0);
            float shaped = mix(ellipse, rectangle, clamp(-uVignette.w, 0.0, 1.0));
            float radius = mix(shaped, circle, clamp(uVignette.w, 0.0, 1.0));
            float halfFeather = clamp(uVignette.z * 0.5, 0.0001, 0.5);
            float t = smoothstep(uVignette.y - halfFeather, uVignette.y + halfFeather, radius);
            return max(1.0 + uVignette.x * t, 0.0);
        }

        void main() {
            vec4 src = texture(uTex0, vTexCoord);
            vec3 srgb = clamp(src.rgb, 0.0, 1.0);
            if (uCompareOriginal == 1) {
                fragColor = vec4(srgb, src.a);
                return;
            }
            // Global post-crop UV: a column band renders only a sub-rectangle, but grain, vignette
            // and dither must evaluate the same whole-image coordinates for zero visible seams.
            vec2 globalUv = vTexCoord * uTileUvTransform.xy + uTileUvTransform.zw;
            vec2 pixelScale = max(uTilePixelScale, vec2(1.0));
            // Snap to the integer output pixel so the grain lattice is bit-identical whether the
            // frame was rendered in one pass or as column bands with slightly different UV math.
            vec2 pixelIndex = floor(globalUv * pixelScale);
            vec2 snappedUv = (pixelIndex + vec2(0.5)) / pixelScale;

            float subjectAlpha = 1.0;
            if (uSubjectMode != 0) {
                vec2 subjectUv = vTexCoord;
                if (uSubjectLocal == 0) {
                    vec3 mapped = uSubjectUvMatrix * vec3(globalUv, 1.0);
                    subjectUv = mapped.xy / max(mapped.z, 1e-6);
                    if (uHasSubjectWarpTex == 1) {
                        vec2 warp = texture(uSubjectWarpTex, subjectUv).xy *
                            uSubjectWarpScale + uSubjectWarpOffset;
                        subjectUv += warp;
                    }
                }
                vec4 subject = texture(uSubjectTex, subjectUv);
                subjectAlpha = subject.a;
                // On the fully opaque body the already-graded source is the better foreground: it
                // carries Pass B/C tone and colour edits that the source-space FMGE estimate cannot.
                // Only the transition band (hair) takes the decontaminated colour.
                float bodyWeight = smoothstep(0.85, 1.0, subjectAlpha);
                vec3 subjectForeground = mix(subject.rgb, src.rgb, bodyWeight);
                if (uSubjectMode == 1) {
                    // Solid backdrop: compose the decontaminated foreground over the chosen colour,
                    // then let LUT / grain / vignette act on the whole frame.
                    srgb = subjectForeground * subjectAlpha + uSubjectColor.rgb * (1.0 - subjectAlpha);
                } else {
                    // Transparent cut-out: effects must act on the foreground only. The checkerboard
                    // is presentation-only and is added after the effects below.
                    srgb = subjectForeground;
                }
            }
            if (uLutActive == 1 && uLutSize >= 2.0) {
                srgb = mix(srgb, tetrahedralLut(srgb), uLutStrength);
            }
            float luminance = dot(srgb, LUMA_WEIGHTS);
            srgb += vec3(grainOffset(snappedUv, luminance));
            srgb *= vec3(vignetteGain(globalUv));
            float ditherX = hash21(pixelIndex, uGrain.w);
            float ditherY = hash21(pixelIndex, uGrain.w + 31.0);
            float dither = (ditherX - ditherY) * uDitherAmp;
            vec3 finalColor = clamp(srgb + vec3(dither), 0.0, 1.0);
            if (uSubjectMode == 2) {
                if (uSubjectChecker == 1) {
                    vec2 cell = floor(globalUv * pixelScale / 12.0);
                    float parity = mod(cell.x + cell.y, 2.0);
                    vec3 checker = mix(vec3(0.18), vec3(0.26), parity);
                    fragColor = vec4(mix(checker, finalColor, subjectAlpha), 1.0);
                } else {
                    // Alpha-preserving export: RGB stays the decontaminated foreground so a
                    // downscaler in the gallery cannot bleed the checkerboard into the edge.
                    fragColor = vec4(finalColor, subjectAlpha);
                }
                return;
            }
            fragColor = vec4(finalColor, src.a);
        }
    """.trimIndent()
}
