package cn.devcxl.photosync.editor.render

/**
 * GLSL snippets shared by the Pass B relight (`ExposureShaderSources`) and the Pass D composite
 * (`EffectsOutputShaderSources`).
 *
 * The snippets assume the caller has already declared their uniforms and provides a
 * `sampleTex2D(sampler2D, vec2)` alias for its GLSL dialect. Both passes evaluate the same
 * `skyCoverage` mapping, so a preview and a full-resolution column band sample exactly the same
 * source-space mask pixel.
 */
internal object SkyShaderSources {

    /** Uniforms of the mask mapping and the Pass B relight. */
    val RELIGHT_UNIFORMS = """
        uniform int uSkyRelightActive;
        uniform sampler2D uSkyMaskTex;
        uniform mat3 uSkyUvMatrix;
        uniform sampler2D uSkyWarpTex;
        uniform int uHasSkyWarpTex;
        uniform vec2 uSkyWarpScale;
        uniform vec2 uSkyWarpOffset;
        uniform vec4 uSkyTileUv;
        uniform vec2 uSkyPixelScale;
        uniform vec4 uSkyParams;
        uniform vec4 uSkyRelightParams;
        uniform float uSkyRelightDeltaL;
    """.trimIndent()

    /** Extra uniforms of the Pass D composite. */
    val COMPOSITE_UNIFORMS = """
        uniform int uSkyActive;
        uniform int uSkyProcedural;
        uniform sampler2D uSkyAssetTex;
        uniform vec3 uSkyZenithColor;
        uniform vec3 uSkyHorizonColor;
        uniform vec3 uSkySunColor;
        uniform vec3 uSkySun;
        uniform float uSkyHaze;
    """.trimIndent()

    /**
     * Maps an output-space UV to the source-space mask coordinate.
     *
     * `uSkyTileUv` turns a bake band's local UV into the global post-crop UV; `uSkyUvMatrix` is the
     * Pass A inverse homography; the optional warp texture mirrors the liquify/portrait displacement.
     * `uSkyParams.z` applies the two-pixel mask erosion around the frame centre, and `uSkyParams.w`
     * switches the mask to a band-aligned plane produced by the bake's per-band guided filter.
     */
    val SAMPLING_FUNCTIONS = """
        vec2 skyMaskCoords(vec2 outputUv) {
            if (uSkyParams.w > 0.5) {
                return outputUv;
            }
            vec2 globalUv = outputUv * uSkyTileUv.xy + uSkyTileUv.zw;
            float shortEdge = max(min(uSkyPixelScale.x, uSkyPixelScale.y), 1.0);
            float edgeScale = 1.0 + uSkyParams.z * 2.0 / shortEdge;
            vec2 shifted = vec2(0.5) + (globalUv - vec2(0.5)) * edgeScale;
            vec3 mapped = uSkyUvMatrix * vec3(shifted, 1.0);
            vec2 uv = mapped.xy / max(mapped.z, 1e-6);
            if (uHasSkyWarpTex == 1) {
                vec2 warp = sampleTex2D(uSkyWarpTex, uv).xy * uSkyWarpScale + uSkyWarpOffset;
                uv += warp;
            }
            return uv;
        }

        float skyCoverage(vec2 outputUv) {
            return clamp(sampleTex2D(uSkyMaskTex, skyMaskCoords(outputUv)).r, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * Pass B foreground relight.
     *
     * `weight = relight × (1 - skyMask) × smoothstep(0.08, 0.75, L_fg)` keeps the replacement sky
     * itself and deep shadows out of the adjustment; the chroma offset is the CPU-computed inverse
     * OKLab delta so the GLSL stays three multiplies.
     */
    val RELIGHT_FUNCTIONS = """
        vec3 applySkyRelight(vec3 linear, vec2 outputUv) {
            if (uSkyRelightActive == 0) {
                return linear;
            }
            float coverage = skyCoverage(outputUv);
            float foreground = 1.0 - coverage;
            float luminance = clamp(dot(linear, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
            float weight = foreground * smoothstep(0.08, 0.75, luminance) * uSkyRelightParams.w;
            vec3 relit = linear + uSkyRelightParams.xyz * weight;
            relit *= 1.0 + uSkyRelightDeltaL * weight;
            return max(relit, vec3(0.0));
        }
    """.trimIndent()

    /**
     * Pass D composite: replacement colour, feathered mask, and transition-band spill suppression.
     *
     * The caller invokes this **before** the 3D LUT, grain and vignette stages.
     */
    val COMPOSITE_FUNCTIONS = """
        vec3 proceduralSkyColour(vec2 globalUv) {
            float worldV = clamp(globalUv.y - uSkyParams.x, 0.0, 1.0);
            float up = 1.0 - worldV;
            float gradient = pow(up, 0.8);
            // A soft band just above the horizon prevents the last few pixels of gradient from
            // turning into a visible line against the feathered replacement mask.
            float horizonBand = 0.02 + 0.10 * uSkyParams.y;
            gradient = mix(gradient, 0.0, smoothstep(1.0 - horizonBand, 1.0, worldV) * 0.65);
            vec3 base = mix(uSkyHorizonColor, uSkyZenithColor, gradient);
            vec2 sunDelta = vec2(globalUv.x - uSkySun.x, worldV - uSkySun.y);
            float glow = exp(-dot(sunDelta, sunDelta) / 0.0256);
            float haze = pow(1.0 - up, 3.0) * uSkyHaze;
            return clamp(base + uSkySunColor * (glow * uSkySun.z + haze * 0.18), 0.0, 1.0);
        }

        vec3 replacementSkyColour(vec2 globalUv) {
            if (uSkyProcedural == 1) {
                return proceduralSkyColour(globalUv);
            }
            vec2 uv = vec2(globalUv.x, clamp(globalUv.y - uSkyParams.x, 0.0, 1.0));
            return clamp(sampleTex2D(uSkyAssetTex, uv).rgb, 0.0, 1.0);
        }

        /** Local sky mean used by the transition-band spill suppression. */
        vec3 skyNeighbourhoodMean(vec2 globalUv) {
            vec2 offset = vec2(0.012, 0.012);
            vec3 sum = replacementSkyColour(globalUv);
            sum += replacementSkyColour(globalUv + vec2(offset.x, 0.0));
            sum += replacementSkyColour(globalUv - vec2(offset.x, 0.0));
            sum += replacementSkyColour(globalUv + vec2(0.0, offset.y));
            sum += replacementSkyColour(globalUv - vec2(0.0, offset.y));
            return sum / 5.0;
        }

        vec3 applySkyComposite(vec3 srgb, vec2 outputUv, vec2 globalUv) {
            if (uSkyActive == 0) {
                return srgb;
            }
            float raw = skyCoverage(outputUv);
            float band = 0.05 + 0.40 * uSkyParams.y;
            float coverage = smoothstep(0.5 - band, 0.5 + band, raw);
            vec3 newSky = replacementSkyColour(globalUv);
            vec3 composited = mix(srgb, newSky, coverage);
            float edge = smoothstep(0.05, 0.5, raw) * (1.0 - smoothstep(0.5, 0.95, raw));
            if (edge <= 0.0) {
                return composited;
            }
            // The spill tint is a local sky average instead of the single pixel under the edge,
            // which keeps branches and roof lines from picking up a noisy cyan/white rim.
            vec3 spillSky = skyNeighbourhoodMean(globalUv);
            return mix(composited, spillSky, edge * 0.20 * (1.0 - coverage));
        }
    """.trimIndent()
}
