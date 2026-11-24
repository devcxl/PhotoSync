#pragma once

#include "libraw.h"

namespace photosync::raw {

/**
 * Caps LibRaw's histogram auto-brightening gain so low-key / night photos are not pushed past
 * +2.0 EV (4.0x) into heavy shadow noise, and returns the gain the browse page effectively
 * applied.
 *
 * The histogram is populated by `dcraw_process()` regardless of `no_auto_bright`, so this must
 * be called after processing. LibRaw normalizes the linear output by
 * `imax = (t_white << 3) / bright`, therefore the equivalent linear-domain gain is
 * `65536 / imax == 8192 / t_white`.
 *
 * @param proc Processed LibRaw instance whose histogram has been built.
 * @param params Output parameters of the same instance; may be updated to the capped values.
 * @return Effective auto-bright gain in `[1.0, 4.0]`, or `1.0` when no brightening applies.
 */
float clampAutoBrightGain(LibRaw& proc, libraw_output_params_t& params);

}  // namespace photosync::raw
