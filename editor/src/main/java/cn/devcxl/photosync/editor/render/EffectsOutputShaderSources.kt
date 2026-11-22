package cn.devcxl.photosync.editor.render

/**
 * GLSL ES 3.00 source of Pass D (`EffectsOutputPass`): tetrahedral 3D LUT, post-crop film grain,
 * post-crop vignette and `1/255` triangular dithering.
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
            if (uLutActive == 1 && uLutSize >= 2.0) {
                srgb = mix(srgb, tetrahedralLut(srgb), uLutStrength);
            }
            float luminance = dot(srgb, LUMA_WEIGHTS);
            srgb += vec3(grainOffset(vTexCoord, luminance));
            srgb *= vec3(vignetteGain(vTexCoord));
            float ditherX = hash21(floor(gl_FragCoord.xy), uGrain.w);
            float ditherY = hash21(floor(gl_FragCoord.xy), uGrain.w + 31.0);
            float dither = (ditherX - ditherY) * uDitherAmp;
            fragColor = vec4(clamp(srgb + vec3(dither), 0.0, 1.0), src.a);
        }
    """.trimIndent()
}
