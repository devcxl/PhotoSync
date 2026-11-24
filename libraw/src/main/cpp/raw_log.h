#pragma once

#include <cstdint>
#include <ctime>

#include "libraw.h"

namespace photosync::raw {

/** Returns a non-null LibRaw error description for @p code. */
inline const char* safeLibRawError(int code) {
    const char* message = libraw_strerror(code);
    return message ? message : "unknown error";
}

/** Monotonic milliseconds used for decode timing logs. */
inline int64_t monotonicMillis() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

}  // namespace photosync::raw
