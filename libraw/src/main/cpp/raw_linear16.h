#pragma once

#include <cstdint>

namespace photosync::raw {

/** Output pixel dimensions of a linear RAW decode. */
struct LinearTargetSize {
    uint32_t width;
    uint32_t height;
};

/**
 * Fits a source size into `maxLongEdge` while preserving aspect ratio.
 *
 * @param maxLongEdge Maximum allowed long edge in pixels; `0` keeps the source size.
 */
LinearTargetSize fitLongEdge(uint32_t width, uint32_t height, uint32_t maxLongEdge);

/**
 * Converts a 16-bit, 3-channel linear RGB image into interleaved `GL_RGBA16F` texels.
 *
 * When `target` is smaller than the source the image is area-averaged in 16-bit linear light,
 * which keeps the drag proxy free of aliasing without a second LibRaw demosaic. Alpha is
 * written as `1.0` (`0x3C00`) because RAW sources are always fully opaque.
 *
 * @param srcRgb16 Source samples, row-major, 3 channels of little-endian `uint16_t`.
 * @param target Destination size; must not exceed the source dimensions.
 * @param dstRgba16f Destination buffer of `target.width * target.height * 8` bytes.
 * @return `false` when the arguments are inconsistent.
 */
bool writeLinearRgba16f(
    const uint8_t* srcRgb16,
    uint32_t srcWidth,
    uint32_t srcHeight,
    const LinearTargetSize& target,
    uint8_t* dstRgba16f
);

/** Encodes a single-precision value as IEEE 754 binary16 bits (round-to-nearest-even). */
uint16_t halfFromFloat(float value);

}  // namespace photosync::raw
