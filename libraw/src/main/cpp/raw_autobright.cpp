#include "raw_autobright.h"

#include <algorithm>

#include <android/log.h>

#define LOG_TAG "LibRawJNI"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace photosync::raw {

namespace {

constexpr int kMinAutoBrightWhite = 2048;  // 8192 / 2048 = 4.0x (+2.0 EV cap)
constexpr float kMaxAutoBrightGain = 4.0f;
constexpr int kHistogramStartBin = 0x2000;
constexpr int kHistogramFloorBin = 32;

/**
 * Returns the per-channel percentile bin LibRaw would use as the white point, or 0 when the
 * histogram is unavailable.
 */
int resolveWhiteBin(LibRaw& proc, const libraw_output_params_t& params) {
    libraw_internal_data_t* internal = proc.get_internal_data_pointer();
    if (!internal || !internal->output_data.histogram) {
        return 0;
    }
    const int width = proc.imgdata.sizes.width;
    const int height = proc.imgdata.sizes.height;
    const int colors = proc.imgdata.idata.colors;
    int percentileCount = static_cast<int>(
        static_cast<double>(width) * static_cast<double>(height) * params.auto_bright_thr);
    if (internal->internal_output_params.fuji_width) {
        percentileCount /= 2;
    }
    int whiteBin = 0;
    for (int channel = 0; channel < colors && channel < 4; ++channel) {
        int bin = kHistogramStartBin;
        int total = 0;
        while (--bin > kHistogramFloorBin) {
            if ((total += internal->output_data.histogram[channel][bin]) > percentileCount) {
                break;
            }
        }
        whiteBin = std::max(whiteBin, bin);
    }
    return whiteBin;
}

}  // namespace

float clampAutoBrightGain(LibRaw& proc, libraw_output_params_t& params) {
    const int whiteBin = resolveWhiteBin(proc, params);
    if (whiteBin <= 0) {
        return 1.0f;
    }
    if (whiteBin < kMinAutoBrightWhite) {
        params.no_auto_bright = 1;
        params.bright = kMaxAutoBrightGain;
        ALOGI("Auto-bright capped: t_white=%d -> bright=%.2fx (+2.0 EV max)",
              whiteBin, params.bright);
        return kMaxAutoBrightGain;
    }
    const float gain = std::max(1.0f, 8192.0f / static_cast<float>(whiteBin));
    ALOGI("Auto-bright active: t_white=%d (gain=%.2fx)", whiteBin, gain);
    return gain;
}

}  // namespace photosync::raw
