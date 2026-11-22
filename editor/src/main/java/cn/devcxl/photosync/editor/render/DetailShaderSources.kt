package cn.devcxl.photosync.editor.render

/**
 * GLSL ES 3.00 sources of `SpatialDetailChain` (physical Pass C).
 *
 * One program serves all four sub-passes; `uStage` selects the math so every stage shares the same
 * uniform block and the chain only has to swap the bound texture:
 * `1 = noise reduction`, `2 = dehaze`, `3 = clarity/texture`, `4 = sharpen`.
 *
 * Each branch mirrors `PassCDetailMath` expression for expression, and Clarity/Texture/Dehaze sample
 * the packed Pre-pass texture through `uTileUvTransform`, so a column-band bake can offset the guide
 * lookup by the band's global UV origin without changing the shader.
 */
internal object DetailShaderSources {

    val VERTEX_SHADER = """
        #version 300 es
        in vec2 aPosition;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    val FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;

        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform sampler2D uTex0;
        uniform sampler2D uPrepassTex;
        uniform sampler2D uSkinMaskTex;
        uniform int uHasPrepass;
        uniform int uHasSkinMask;
        uniform int uStage;
        uniform vec2 uTexelSize;
        uniform vec4 uTileUvTransform;
        uniform vec3 uNrParams;
        uniform float uDehazeStrength;
        uniform vec3 uDehazeA;
        uniform vec2 uClarityTexture;
        uniform vec4 uSharpen;
        uniform float uDitherAmp;

        const vec3 LUMA_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);
        const float HIGHPASS_LIMIT = 0.08;
        const float TRANSMISSION_FLOOR = 0.1;
        const float THRESHOLD_SCALE = 0.10;
        const float THRESHOLD_SOFTNESS = 0.02;
        const float MASK_GRADIENT_REFERENCE = 0.12;
        const float CLARITY_GAIN = 1.0;
        const float TEXTURE_GAIN = 1.0;
        const float CB_SCALE = 1.8556;
        const float CR_SCALE = 1.5748;

        float luminance(vec3 color) {
            return dot(color, LUMA_WEIGHTS);
        }

        float smoothStep(float edge0, float edge1, float value) {
            float span = max(edge1 - edge0, 1e-6);
            float t = clamp((value - edge0) / span, 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }

        float midtoneBell(float y) {
            float clamped = clamp(y, 0.0, 1.0);
            return clamp(4.0 * clamped * (1.0 - clamped), 0.0, 1.0);
        }

        float highPass(float y, float base) {
            return clamp(y - base, -HIGHPASS_LIMIT, HIGHPASS_LIMIT);
        }

        float transmission(float raw) {
            return max(raw, TRANSMISSION_FLOOR);
        }

        float triDither(vec2 position) {
            float n1 = fract(sin(dot(position, vec2(12.9898, 78.233))) * 43758.5453);
            float n2 = fract(sin(dot(position + vec2(1.0, 1.0), vec2(39.346, 11.135))) * 43758.5453);
            return (n1 - n2) * uDitherAmp;
        }

        float chromaTapWeight(int offset) {
            if (offset == 0) return 3.0;
            if (offset == 1 || offset == -1) return 2.0;
            return 1.0;
        }

        vec3 sampleAt(vec2 uv) {
            return texture(uTex0, uv).rgb;
        }

        vec2 guideUv() {
            return vTexCoord * uTileUvTransform.xy + uTileUvTransform.zw;
        }

        // Stage 1: 3x3 guided luma NR with detail blend-back plus 5x5 edge-guided CbCr NR.
        vec3 noiseReduction(vec3 srgb) {
            float y = luminance(srgb);
            float sum = y;
            float sumSquares = y * y;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    float sampleY = luminance(
                        sampleAt(vTexCoord + vec2(float(dx), float(dy)) * uTexelSize)
                    );
                    sum += sampleY;
                    sumSquares += sampleY * sampleY;
                }
            }
            float mean = sum / 9.0;
            float variance = max(sumSquares / 9.0 - mean * mean, 0.0);
            float coefficient = variance / (variance + 0.001);
            float smoothed = coefficient * (y - mean) + mean;
            float restored = mix(smoothed, y, clamp(uNrParams.z, 0.0, 1.0));
            float newY = y + (restored - y) * clamp(uNrParams.x, 0.0, 1.0);
            if (uNrParams.y <= 0.0) {
                return clamp(srgb + vec3(newY - y), 0.0, 1.0);
            }
            vec2 chroma = vec2((srgb.b - y) / CB_SCALE, (srgb.r - y) / CR_SCALE);
            vec2 accum = vec2(0.0);
            float weightSum = 0.0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    vec3 sampleColor = sampleAt(
                        vTexCoord + vec2(float(dx), float(dy)) * uTexelSize
                    );
                    float sampleY = luminance(sampleColor);
                    vec2 sampleChroma = vec2(
                        (sampleColor.b - sampleY) / CB_SCALE,
                        (sampleColor.r - sampleY) / CR_SCALE
                    );
                    float difference = sampleY - y;
                    float weight = chromaTapWeight(dx) * chromaTapWeight(dy) *
                        exp(-difference * difference / (2.0 * 0.08 * 0.08));
                    accum += sampleChroma * weight;
                    weightSum += weight;
                }
            }
            vec2 smoothedChroma = accum / max(weightSum, 1e-6);
            vec2 blended = mix(chroma, smoothedChroma, clamp(uNrParams.y, 0.0, 1.0));
            float red = newY + CR_SCALE * blended.y;
            float blue = newY + CB_SCALE * blended.x;
            float green = (newY - 0.2126 * red - 0.0722 * blue) / 0.7152;
            return clamp(vec3(red, green, blue), 0.0, 1.0);
        }

        // Stage 2: dark-channel dehaze against the global A and the floored coarse transmission.
        vec3 dehaze(vec3 srgb) {
            if (uHasPrepass == 0) return srgb;
            float t = transmission(texture(uPrepassTex, guideUv()).b);
            vec3 atmospheric = uDehazeA * (1.0 - t);
            vec3 target = (uDehazeStrength > 0.0)
                ? (srgb - atmospheric) / t
                : srgb * t + atmospheric;
            return clamp(mix(srgb, target, abs(uDehazeStrength)), 0.0, 1.0);
        }

        // Stage 3: bounded Clarity / Texture high-pass weighted by the midtone bell.
        vec3 clarityTexture(vec3 srgb) {
            if (uHasPrepass == 0) return srgb;
            vec4 guide = texture(uPrepassTex, guideUv());
            float y = luminance(srgb);
            float delta = highPass(y, guide.r) * uClarityTexture.x * CLARITY_GAIN +
                highPass(y, guide.g) * uClarityTexture.y * TEXTURE_GAIN;
            return clamp(srgb + vec3(delta * midtoneBell(y)), 0.0, 1.0);
        }

        float localGradient() {
            float left = luminance(sampleAt(vTexCoord - vec2(uTexelSize.x, 0.0)));
            float right = luminance(sampleAt(vTexCoord + vec2(uTexelSize.x, 0.0)));
            float up = luminance(sampleAt(vTexCoord - vec2(0.0, uTexelSize.y)));
            float down = luminance(sampleAt(vTexCoord + vec2(0.0, uTexelSize.y)));
            return max(abs(right - left), abs(down - up));
        }

        // Stage 4: Y-channel edge-gated unsharp mask, suppressed by (1 - skinMask).
        vec3 unsharpMask(vec3 srgb) {
            float amount = clamp(uSharpen.x, 0.0, 1.5);
            if (amount <= 0.0) return srgb;
            float stepPixels = clamp(floor(uSharpen.y + 0.5), 1.0, 3.0);
            vec2 stepUv = uTexelSize * stepPixels;
            float y = luminance(srgb);
            float blurred = 0.0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    float weightX = (dx == 0) ? 0.5 : 0.25;
                    float weightY = (dy == 0) ? 0.5 : 0.25;
                    blurred += luminance(
                        sampleAt(vTexCoord + vec2(float(dx), float(dy)) * stepUv)
                    ) * weightX * weightY;
                }
            }
            float detail = y - blurred;
            float knee = clamp(uSharpen.z, 0.0, 1.0) * THRESHOLD_SCALE;
            float gate = (knee <= 0.0) ? 1.0 : smoothStep(knee, knee + THRESHOLD_SOFTNESS, abs(detail));
            float edge = smoothStep(0.0, MASK_GRADIENT_REFERENCE, localGradient());
            float maskWeight = 1.0 + (edge - 1.0) * clamp(uSharpen.w, 0.0, 1.0);
            float skin = (uHasSkinMask == 1)
                ? 1.0 - clamp(texture(uSkinMaskTex, vTexCoord).r, 0.0, 1.0)
                : 1.0;
            return clamp(srgb + vec3(amount * detail * gate * maskWeight * skin), 0.0, 1.0);
        }

        void main() {
            vec4 source = texture(uTex0, vTexCoord);
            vec3 result = source.rgb;
            if (uStage == 1) {
                result = noiseReduction(source.rgb);
            } else if (uStage == 2) {
                result = dehaze(source.rgb);
            } else if (uStage == 3) {
                result = clarityTexture(source.rgb);
            } else if (uStage == 4) {
                result = unsharpMask(source.rgb);
            }
            float dither = triDither(gl_FragCoord.xy);
            fragColor = vec4(clamp(result + vec3(dither), 0.0, 1.0), source.a);
        }
    """.trimIndent()
}
