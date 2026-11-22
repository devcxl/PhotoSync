#include "raw_linear16.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace photosync::raw {

namespace {

constexpr float kInv65535 = 1.0f / 65535.0f;
constexpr uint32_t kSrcChannels = 3;
constexpr size_t kDstBytesPerPixel = 8;
constexpr uint16_t kHalfOne = 0x3C00;

inline uint32_t rangeBegin(uint32_t index, uint32_t sourceLength, uint32_t targetLength) {
    return static_cast<uint32_t>(
        (static_cast<uint64_t>(index) * sourceLength) / targetLength);
}

inline uint32_t rangeEnd(uint32_t index, uint32_t sourceLength, uint32_t targetLength) {
    const uint32_t begin = rangeBegin(index, sourceLength, targetLength);
    const uint32_t end = static_cast<uint32_t>(
        (static_cast<uint64_t>(index + 1) * sourceLength) / targetLength);
    return std::max(end, begin + 1);
}

inline uint16_t readSample16(const uint8_t* row, uint32_t x, uint32_t channel) {
    uint16_t value = 0;
    const size_t offset = (static_cast<size_t>(x) * kSrcChannels + channel) * sizeof(uint16_t);
    std::memcpy(&value, row + offset, sizeof(uint16_t));
    return value;
}

inline void writeHalfRgba(uint8_t* dst, float red, float green, float blue) {
    const uint16_t texel[4] = {
        halfFromFloat(red),
        halfFromFloat(green),
        halfFromFloat(blue),
        kHalfOne
    };
    std::memcpy(dst, texel, sizeof(texel));
}

void convertRowIdentity(const uint8_t* srcRow, uint32_t width, uint8_t* dstRow) {
    for (uint32_t x = 0; x < width; ++x) {
        writeHalfRgba(
            dstRow + static_cast<size_t>(x) * kDstBytesPerPixel,
            static_cast<float>(readSample16(srcRow, x, 0)) * kInv65535,
            static_cast<float>(readSample16(srcRow, x, 1)) * kInv65535,
            static_cast<float>(readSample16(srcRow, x, 2)) * kInv65535
        );
    }
}

void convertRowAveraged(
    const uint8_t* srcData,
    uint32_t srcWidth,
    uint32_t srcHeight,
    uint32_t dstWidth,
    uint32_t dstHeight,
    uint32_t dstY,
    uint8_t* dstRow
) {
    const uint32_t y0 = rangeBegin(dstY, srcHeight, dstHeight);
    const uint32_t y1 = rangeEnd(dstY, srcHeight, dstHeight);
    for (uint32_t x = 0; x < dstWidth; ++x) {
        const uint32_t x0 = rangeBegin(x, srcWidth, dstWidth);
        const uint32_t x1 = rangeEnd(x, srcWidth, dstWidth);
        float sum[3] = {0.0f, 0.0f, 0.0f};
        for (uint32_t sy = y0; sy < y1; ++sy) {
            const uint8_t* srcRow = srcData + static_cast<size_t>(sy) * srcWidth * kSrcChannels * 2;
            for (uint32_t sx = x0; sx < x1; ++sx) {
                for (uint32_t channel = 0; channel < kSrcChannels; ++channel) {
                    sum[channel] += static_cast<float>(readSample16(srcRow, sx, channel));
                }
            }
        }
        const float divisor = static_cast<float>((y1 - y0) * (x1 - x0));
        writeHalfRgba(
            dstRow + static_cast<size_t>(x) * kDstBytesPerPixel,
            sum[0] / divisor * kInv65535,
            sum[1] / divisor * kInv65535,
            sum[2] / divisor * kInv65535
        );
    }
}

}  // namespace

LinearTargetSize fitLongEdge(uint32_t width, uint32_t height, uint32_t maxLongEdge) {
    const uint32_t longEdge = std::max(width, height);
    if (maxLongEdge == 0 || longEdge <= maxLongEdge) {
        return LinearTargetSize{width, height};
    }
    const double scale = static_cast<double>(maxLongEdge) / static_cast<double>(longEdge);
    return LinearTargetSize{
        std::max(1u, static_cast<uint32_t>(std::lround(static_cast<double>(width) * scale))),
        std::max(1u, static_cast<uint32_t>(std::lround(static_cast<double>(height) * scale)))
    };
}

bool writeLinearRgba16f(
    const uint8_t* srcRgb16,
    uint32_t srcWidth,
    uint32_t srcHeight,
    const LinearTargetSize& target,
    uint8_t* dstRgba16f
) {
    if (!srcRgb16 || !dstRgba16f || srcWidth == 0 || srcHeight == 0) {
        return false;
    }
    if (target.width == 0 || target.height == 0 ||
        target.width > srcWidth || target.height > srcHeight) {
        return false;
    }
    const bool identity = target.width == srcWidth && target.height == srcHeight;

#if defined(_OPENMP)
#pragma omp parallel for schedule(static) if (target.height >= 64)
#endif
    for (uint32_t y = 0; y < target.height; ++y) {
        uint8_t* dstRow = dstRgba16f + static_cast<size_t>(y) * target.width * kDstBytesPerPixel;
        if (identity) {
            const uint8_t* srcRow = srcRgb16 + static_cast<size_t>(y) * srcWidth * kSrcChannels * 2;
            convertRowIdentity(srcRow, target.width, dstRow);
        } else {
            convertRowAveraged(
                srcRgb16, srcWidth, srcHeight, target.width, target.height, y, dstRow);
        }
    }
    return true;
}

uint16_t halfFromFloat(float value) {
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    const uint32_t sign = (bits >> 16) & 0x8000u;
    const int32_t rawExponent = static_cast<int32_t>((bits >> 23) & 0xFFu);
    const uint32_t mantissa = bits & 0x7FFFFFu;
    if (rawExponent == 0xFF) {
        return static_cast<uint16_t>(sign | 0x7C00u | (mantissa >> 13));
    }
    const int32_t exponent = rawExponent - 127 + 15;
    if (exponent >= 0x1F) {
        return static_cast<uint16_t>(sign | 0x7C00u);
    }
    if (exponent <= 0) {
        if (exponent < -10) {
            return static_cast<uint16_t>(sign);
        }
        const uint32_t withHiddenBit = mantissa | 0x800000u;
        const int32_t shift = 14 - exponent;
        uint32_t half = withHiddenBit >> shift;
        const uint32_t remainder = withHiddenBit & ((1u << shift) - 1u);
        const uint32_t halfway = 1u << (shift - 1);
        if (remainder > halfway || (remainder == halfway && (half & 1u) != 0u)) {
            ++half;
        }
        return static_cast<uint16_t>(sign | half);
    }
    uint32_t half = (static_cast<uint32_t>(exponent) << 10) | (mantissa >> 13);
    const uint32_t remainder = mantissa & 0x1FFFu;
    if (remainder > 0x1000u || (remainder == 0x1000u && (half & 1u) != 0u)) {
        ++half;
    }
    return static_cast<uint16_t>(sign | half);
}

}  // namespace photosync::raw
