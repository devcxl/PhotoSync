#pragma once

#include "libraw.h"

namespace photosync::raw {

/**
 * Applies the 16-bit linear sRGB decode configuration shared by the preview decoder and the
 * full-resolution Tier 2 native residency decoder.
 *
 * The output stays in scene-linear light (`gamm[0] = gamm[1] = 1`) and auto-brightening is left to
 * the GPU so the RAW highlight headroom survives the trip into `RGBA16F` texels.
 */
inline void configureLinearDecode(libraw_output_params_t& params) {
    params.output_bps = 16;
    params.output_color = 1;  // sRGB primaries
    params.output_tiff = 0;   // PPM/BITMAP output (in-memory)
    params.gamm[0] = 1.0;     // Linear transfer function (no sRGB encode)
    params.gamm[1] = 1.0;

    params.use_camera_wb = 1;
    params.use_auto_wb = 0;
    params.no_auto_bright = 1;
    params.bright = 1.0f;

    params.user_qual = 2;
    params.med_passes = 0;
    params.fbdd_noiserd = 0;
    params.threshold = 0.0;
    params.highlight = 2;

    params.aber[0] = 1.0;
    params.aber[1] = 1.0;
    params.aber[2] = 1.0;
}

}  // namespace photosync::raw
