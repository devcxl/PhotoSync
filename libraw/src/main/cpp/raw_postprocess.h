#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace photosync::raw {

/**
 * Post-processing tone & color parameters applied to gamma-encoded sRGB data
 * after LibRaw demosaicing.
 */
struct ToneCurveParams {
    // Power curve exponent (< 1.0 lifts shadows and midtones smoothly).
    float shadowMidtoneGamma = 0.78f;
    // Blend weight [0, 1] for smoothstep S-curve contrast and highlight roll-off.
    float sCurveStrength = 0.12f;
    // Adaptive saturation boost [0, 1]; muted tones gain up to (1 + vibrance)x.
    float vibrance = 0.18f;
    // Effective ISO (camera ISO * auto-bright gain) driving fast post-process denoising.
    // Values <= 150 skip denoising completely.
    float effectiveIso = 0.0f;
};

/**
 * Evaluates the photographic tone curve for a normalized sRGB sample in [0, 1].
 */
float evaluateToneCurve(float x, const ToneCurveParams& params = {});

/**
 * Builds a 65536-entry LUT mapping 16-bit sRGB [0..65535] -> [0.0f..255.0f].
 */
std::vector<float> buildToneCurveLut16(const ToneCurveParams& params = {});

/**
 * Converts processed LibRaw pixels (8-bit or 16-bit, 1/3/4 channels) into RGBA_8888
 * with tone curve and vibrance applied.
 */
bool writeProcessedToRgba8888(
    const uint8_t* srcData,
    uint32_t width,
    uint32_t height,
    uint16_t bits,
    uint16_t colors,
    uint8_t* dstRgba,
    size_t dstStrideBytes,
    const ToneCurveParams& params = {}
);

/**
 * Converts processed LibRaw pixels (8-bit or 16-bit, 1/3/4 channels) into packed RGB_888
 * with tone curve and vibrance applied.
 */
bool writeProcessedToRgb888(
    const uint8_t* srcData,
    uint32_t width,
    uint32_t height,
    uint16_t bits,
    uint16_t colors,
    uint8_t* dstRgb,
    const ToneCurveParams& params = {}
);

}  // namespace photosync::raw
