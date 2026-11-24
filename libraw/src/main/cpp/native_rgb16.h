#pragma once

#include <jni.h>

#include "libraw.h"

namespace photosync::raw {

/**
 * Full-resolution native `RGB16` residency used by the Tier 2 bake path.
 *
 * The decoded image stays inside LibRaw's own allocation; no second copy is made, so a 61MP sensor
 * costs `~349 MiB` instead of the `~700 MiB` a copy would need. Callers extract sub-rectangles into
 * `RGBA16F` upload buffers on demand and never hold more than one slice on the GPU.
 */
struct Rgb16Image {
    libraw_processed_image_t* image = nullptr;
    float autoBrightGain = 1.0f;
    float effectiveIso = 0.0f;

    uint32_t width() const { return image ? image->width : 0; }
    uint32_t height() const { return image ? image->height : 0; }

    ~Rgb16Image() {
        if (image != nullptr) {
            LibRaw::dcraw_clear_mem(image);
            image = nullptr;
        }
    }
};

/**
 * Decodes @p path into a retained native `RGB16` image.
 *
 * @param halfSize When true LibRaw performs the 2x2 Bayer merge (`100MP -> 25MP`).
 * @return The retained image, or null on failure.
 */
Rgb16Image* decodeRgb16(const char* path, bool halfSize);

/**
 * Extracts one sub-rectangle into interleaved `RGBA16F` texels.
 *
 * @param target Destination buffer; must hold `width * height * 8` bytes.
 * @return False when the rectangle is outside the image or the buffer is too small.
 */
bool extractRgba16fSlice(
    const Rgb16Image& source,
    uint32_t x0,
    uint32_t y0,
    uint32_t width,
    uint32_t height,
    uint8_t* target
);

}  // namespace photosync::raw
