#include "raw_postprocess.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace photosync::raw {

namespace {

constexpr float kInv65535 = 1.0f / 65535.0f;
constexpr float kInv255 = 1.0f / 255.0f;
constexpr float kLumaR = 0.2126f;
constexpr float kLumaG = 0.7152f;
constexpr float kLumaB = 0.0722f;
constexpr float kInvLumaG = 1.0f / kLumaG;
constexpr uint32_t kStripHeight = 128;

inline uint8_t roundClampToU8(float v) {
    const float clamped = std::clamp(v, 0.0f, 255.0f);
    return static_cast<uint8_t>(clamped + 0.5f);
}

inline uint16_t readSample16(const uint8_t* src, size_t index, uint16_t bits) {
    if (bits == 16) {
        uint16_t v16 = 0;
        std::memcpy(&v16, src + index * sizeof(uint16_t), sizeof(uint16_t));
        return v16;
    }
    const uint16_t v8 = src[index];
    return static_cast<uint16_t>((v8 << 8) | v8);
}

inline void applyVibranceAndStoreRgb(
    float r,
    float g,
    float b,
    float vibrance,
    uint8_t* dst
) {
    if (vibrance > 0.0f) {
        const float luma = kLumaR * r + kLumaG * g + kLumaB * b;
        // Fade vibrance down in deep shadows so lifted shadow noise does not get colorized.
        const float shadowFade = std::clamp((luma - 12.0f) / 48.0f, 0.0f, 1.0f);
        const float maxC = std::max(r, std::max(g, b));
        const float minC = std::min(r, std::min(g, b));
        const float sat = (maxC - minC) * kInv255;
        const float scale = 1.0f + vibrance * shadowFade * std::max(0.0f, 1.0f - sat);
        r = luma + (r - luma) * scale;
        g = luma + (g - luma) * scale;
        b = luma + (b - luma) * scale;
    }
    dst[0] = roundClampToU8(r);
    dst[1] = roundClampToU8(g);
    dst[2] = roundClampToU8(b);
}

struct DenoiseConfig {
    int chromaPasses = 0;
    float chromaInvSigmaY2 = 0.0f;
    bool lumaEnabled = false;
    float lumaInvSigma2 = 0.0f;
    float lumaMix = 0.0f;
};

DenoiseConfig resolveDenoiseConfig(float effectiveIso) {
    DenoiseConfig cfg{};
    if (effectiveIso <= 150.0f) {
        return cfg;
    }
    cfg.chromaPasses = (effectiveIso >= 600.0f) ? 2 : 1;
    const float sigmaY = (effectiveIso >= 600.0f) ? 26.0f : 22.0f;
    cfg.chromaInvSigmaY2 = 1.0f / (sigmaY * sigmaY);

    if (effectiveIso >= 600.0f) {
        cfg.lumaEnabled = true;
        const float stops = std::max(0.0f, std::log2(effectiveIso / 600.0f));
        const float sigmaLuma = std::clamp(7.5f + 2.2f * stops, 7.5f, 16.0f);
        cfg.lumaInvSigma2 = 1.0f / (sigmaLuma * sigmaLuma);
        cfg.lumaMix = std::clamp(0.35f + 0.10f * stops, 0.35f, 0.68f);
    }
    return cfg;
}

template <int DstChannels>
bool writeDirectPixels(
    const uint8_t* srcData,
    uint32_t width,
    uint32_t height,
    uint16_t bits,
    uint16_t colors,
    uint8_t* dstData,
    size_t dstStrideBytes,
    const ToneCurveParams& params,
    const std::vector<float>& toneLut
) {
    const size_t bytesPerSample = bits / 8;
    const size_t srcRowBytes = static_cast<size_t>(width) * colors * bytesPerSample;

#if defined(_OPENMP)
#pragma omp parallel for schedule(static) if (height >= 256)
#endif
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* srcRow = srcData + static_cast<size_t>(y) * srcRowBytes;
        uint8_t* dstRow = dstData + static_cast<size_t>(y) * dstStrideBytes;

        if (colors == 1) {
            for (uint32_t x = 0; x < width; ++x) {
                const uint16_t sample = readSample16(srcRow, x, bits);
                const uint8_t v = roundClampToU8(toneLut[sample]);
                uint8_t* d = dstRow + static_cast<size_t>(x) * DstChannels;
                d[0] = v;
                d[1] = v;
                d[2] = v;
                if constexpr (DstChannels == 4) {
                    d[3] = 0xFF;
                }
            }
        } else {
            for (uint32_t x = 0; x < width; ++x) {
                const size_t base = static_cast<size_t>(x) * colors;
                const float r = toneLut[readSample16(srcRow, base + 0, bits)];
                const float g = toneLut[readSample16(srcRow, base + 1, bits)];
                const float b = toneLut[readSample16(srcRow, base + 2, bits)];
                uint8_t* d = dstRow + static_cast<size_t>(x) * DstChannels;
                applyVibranceAndStoreRgb(r, g, b, params.vibrance, d);
                if constexpr (DstChannels == 4) {
                    d[3] = 0xFF;
                }
            }
        }
    }
    return true;
}

template <int DstChannels>
bool writeDenoisedColorPixels(
    const uint8_t* srcData,
    uint32_t width,
    uint32_t height,
    uint16_t bits,
    uint16_t colors,
    uint8_t* dstData,
    size_t dstStrideBytes,
    const ToneCurveParams& params,
    const std::vector<float>& toneLut,
    const DenoiseConfig& cfg
) {
    const size_t bytesPerSample = bits / 8;
    const size_t srcRowBytes = static_cast<size_t>(width) * colors * bytesPerSample;
    const uint32_t halo = static_cast<uint32_t>(std::max(cfg.chromaPasses * 2, cfg.lumaEnabled ? 1 : 0));
    const uint32_t numStrips = (height + kStripHeight - 1) / kStripHeight;
    constexpr float kSpatial5[5] = {1.0f, 4.0f, 6.0f, 4.0f, 1.0f};
    constexpr float kSpatial3[3] = {1.0f, 2.0f, 1.0f};

#if defined(_OPENMP)
#pragma omp parallel for schedule(dynamic, 1) if (numStrips > 1)
#endif
    for (uint32_t stripIdx = 0; stripIdx < numStrips; ++stripIdx) {
        const uint32_t y0 = stripIdx * kStripHeight;
        const uint32_t y1 = std::min(height, y0 + kStripHeight);
        const uint32_t hy0 = (y0 > halo) ? (y0 - halo) : 0;
        const uint32_t hy1 = std::min(height, y1 + halo);
        const uint32_t stripH = hy1 - hy0;
        const size_t stripPixels = static_cast<size_t>(width) * stripH;

        std::vector<float> yBuf(stripPixels);
        std::vector<float> yOutBuf;
        std::vector<float> cbBuf(stripPixels);
        std::vector<float> crBuf(stripPixels);
        std::vector<float> cbTmp(stripPixels);
        std::vector<float> crTmp(stripPixels);

        // 1. Apply 16-bit tone curve LUT and convert strip into opponent (Y, Cb=B-Y, Cr=R-Y).
        for (uint32_t sy = 0; sy < stripH; ++sy) {
            const uint8_t* srcRow = srcData + static_cast<size_t>(hy0 + sy) * srcRowBytes;
            const size_t rowOff = static_cast<size_t>(sy) * width;
            for (uint32_t x = 0; x < width; ++x) {
                const size_t base = static_cast<size_t>(x) * colors;
                const float r = toneLut[readSample16(srcRow, base + 0, bits)];
                const float g = toneLut[readSample16(srcRow, base + 1, bits)];
                const float b = toneLut[readSample16(srcRow, base + 2, bits)];
                const float y = kLumaR * r + kLumaG * g + kLumaB * b;
                yBuf[rowOff + x] = y;
                cbBuf[rowOff + x] = b - y;
                crBuf[rowOff + x] = r - y;
            }
        }

        // 2. Separable edge-guided chroma smoothing (5-tap H + 5-tap V per pass).
        for (int pass = 0; pass < cfg.chromaPasses; ++pass) {
            const uint32_t pad = static_cast<uint32_t>((cfg.chromaPasses - 1 - pass) * 2);
            const uint32_t passY0 = (y0 > pad) ? (y0 - pad - hy0) : 0;
            const uint32_t passY1 = std::min(stripH, (y1 + pad) - hy0);
            const uint32_t hY0 = (passY0 > 2) ? (passY0 - 2) : 0;
            const uint32_t hY1 = std::min(stripH, passY1 + 2);

            for (uint32_t sy = hY0; sy < hY1; ++sy) {
                const size_t rowOff = static_cast<size_t>(sy) * width;
                for (uint32_t x = 0; x < width; ++x) {
                    const float yc = yBuf[rowOff + x];
                    float sumW = 0.0f;
                    float sumCb = 0.0f;
                    float sumCr = 0.0f;
                    for (int dx = -2; dx <= 2; ++dx) {
                        const int nx = std::clamp(static_cast<int>(x) + dx, 0, static_cast<int>(width) - 1);
                        const size_t idx = rowOff + static_cast<size_t>(nx);
                        const float dy = yBuf[idx] - yc;
                        const float w = kSpatial5[dx + 2] / (1.0f + dy * dy * cfg.chromaInvSigmaY2);
                        sumW += w;
                        sumCb += w * cbBuf[idx];
                        sumCr += w * crBuf[idx];
                    }
                    const float invW = 1.0f / sumW;
                    cbTmp[rowOff + x] = sumCb * invW;
                    crTmp[rowOff + x] = sumCr * invW;
                }
            }

            for (uint32_t sy = passY0; sy < passY1; ++sy) {
                const size_t rowOff = static_cast<size_t>(sy) * width;
                for (uint32_t x = 0; x < width; ++x) {
                    const float yc = yBuf[rowOff + x];
                    float sumW = 0.0f;
                    float sumCb = 0.0f;
                    float sumCr = 0.0f;
                    for (int dyOff = -2; dyOff <= 2; ++dyOff) {
                        const int nsy = std::clamp(static_cast<int>(sy) + dyOff, 0, static_cast<int>(stripH) - 1);
                        const size_t idx = static_cast<size_t>(nsy) * width + x;
                        const float dy = yBuf[idx] - yc;
                        const float w = kSpatial5[dyOff + 2] / (1.0f + dy * dy * cfg.chromaInvSigmaY2);
                        sumW += w;
                        sumCb += w * cbTmp[idx];
                        sumCr += w * crTmp[idx];
                    }
                    const float invW = 1.0f / sumW;
                    cbBuf[rowOff + x] = sumCb * invW;
                    crBuf[rowOff + x] = sumCr * invW;
                }
            }
        }

        // 3. Optional fast 3x3 bilateral luma smoothing for high ISO (>= 600).
        const float* finalY = yBuf.data();
        if (cfg.lumaEnabled) {
            yOutBuf.resize(stripPixels);
            const uint32_t outStart = y0 - hy0;
            const uint32_t outEnd = y1 - hy0;
            for (uint32_t sy = outStart; sy < outEnd; ++sy) {
                const size_t rowOff = static_cast<size_t>(sy) * width;
                for (uint32_t x = 0; x < width; ++x) {
                    const float yc = yBuf[rowOff + x];
                    float sumW = 0.0f;
                    float sumY = 0.0f;
                    for (int dyOff = -1; dyOff <= 1; ++dyOff) {
                        const int nsy = std::clamp(static_cast<int>(sy) + dyOff, 0, static_cast<int>(stripH) - 1);
                        const size_t nRowOff = static_cast<size_t>(nsy) * width;
                        const float wy = kSpatial3[dyOff + 1];
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int nx = std::clamp(static_cast<int>(x) + dx, 0, static_cast<int>(width) - 1);
                            const float yn = yBuf[nRowOff + static_cast<size_t>(nx)];
                            const float dY = yn - yc;
                            const float w = (wy * kSpatial3[dx + 1]) / (1.0f + dY * dY * cfg.lumaInvSigma2);
                            sumW += w;
                            sumY += w * yn;
                        }
                    }
                    const float smoothedY = sumY / sumW;
                    yOutBuf[rowOff + x] = yc + cfg.lumaMix * (smoothedY - yc);
                }
            }
            finalY = yOutBuf.data();
        }

        // 4. Reconstruct RGB from (Y', Cb', Cr'), apply shadow-safe vibrance, and store.
        for (uint32_t y = y0; y < y1; ++y) {
            const uint32_t sy = y - hy0;
            const size_t rowOff = static_cast<size_t>(sy) * width;
            uint8_t* dstRow = dstData + static_cast<size_t>(y) * dstStrideBytes;
            for (uint32_t x = 0; x < width; ++x) {
                const size_t idx = rowOff + x;
                const float yVal = finalY[idx];
                const float cb = cbBuf[idx];
                const float cr = crBuf[idx];
                const float r = yVal + cr;
                const float b = yVal + cb;
                const float g = yVal - (kLumaR * cr + kLumaB * cb) * kInvLumaG;
                uint8_t* d = dstRow + static_cast<size_t>(x) * DstChannels;
                applyVibranceAndStoreRgb(r, g, b, params.vibrance, d);
                if constexpr (DstChannels == 4) {
                    d[3] = 0xFF;
                }
            }
        }
    }

    return true;
}

template <int DstChannels>
bool writeProcessedPixels(
    const uint8_t* srcData,
    uint32_t width,
    uint32_t height,
    uint16_t bits,
    uint16_t colors,
    uint8_t* dstData,
    size_t dstStrideBytes,
    const ToneCurveParams& params
) {
    if (!srcData || !dstData || width == 0 || height == 0) {
        return false;
    }
    if (bits != 8 && bits != 16) {
        return false;
    }
    if (colors != 1 && colors < 3) {
        return false;
    }
    if (dstStrideBytes < static_cast<size_t>(width) * DstChannels) {
        return false;
    }

    const std::vector<float> toneLut = buildToneCurveLut16(params);
    const DenoiseConfig denoiseCfg = resolveDenoiseConfig(params.effectiveIso);
    if (colors >= 3 && denoiseCfg.chromaPasses > 0) {
        return writeDenoisedColorPixels<DstChannels>(
            srcData, width, height, bits, colors, dstData, dstStrideBytes, params, toneLut, denoiseCfg
        );
    }

    return writeDirectPixels<DstChannels>(
        srcData, width, height, bits, colors, dstData, dstStrideBytes, params, toneLut
    );
}

}  // namespace

float evaluateToneCurve(float x, const ToneCurveParams& params) {
    const float clamped = std::clamp(x, 0.0f, 1.0f);
    const float lifted = std::pow(clamped, params.shadowMidtoneGamma);
    const float sCurve = lifted * lifted * (3.0f - 2.0f * lifted);
    const float k = std::clamp(params.sCurveStrength, 0.0f, 1.0f);
    return (1.0f - k) * lifted + k * sCurve;
}

std::vector<float> buildToneCurveLut16(const ToneCurveParams& params) {
    std::vector<float> lut(65536);
    for (size_t i = 0; i < lut.size(); ++i) {
        const float x = static_cast<float>(i) * kInv65535;
        lut[i] = evaluateToneCurve(x, params) * 255.0f;
    }
    return lut;
}

bool writeProcessedToRgba8888(
    const uint8_t* srcData,
    uint32_t width,
    uint32_t height,
    uint16_t bits,
    uint16_t colors,
    uint8_t* dstRgba,
    size_t dstStrideBytes,
    const ToneCurveParams& params
) {
    return writeProcessedPixels<4>(
        srcData, width, height, bits, colors, dstRgba, dstStrideBytes, params
    );
}

bool writeProcessedToRgb888(
    const uint8_t* srcData,
    uint32_t width,
    uint32_t height,
    uint16_t bits,
    uint16_t colors,
    uint8_t* dstRgb,
    const ToneCurveParams& params
) {
    const size_t strideBytes = static_cast<size_t>(width) * 3UL;
    return writeProcessedPixels<3>(
        srcData, width, height, bits, colors, dstRgb, strideBytes, params
    );
}

}  // namespace photosync::raw
