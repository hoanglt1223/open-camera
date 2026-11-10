/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <vector>

#include "align.h"
#include "image.h"

namespace mf {

// Multi-frame super-resolution of luma. The half-resolution luma of each frame is treated as the
// sampled signal; sub-pixel displaced samples from all frames are splatted (gathered) with a
// Gaussian kernel onto the full-resolution grid, with robustness weights against misalignment.
// Used for the 2x crop stop, where the HAL frames are a 2x upscale of the native-resolution crop.
void superResolveLuma(const std::vector<const Gray *> &half, int ref,
                      const std::vector<std::vector<Displacement>> &disp, const TileGrid &grid,
                      float noiseSigma, GrayF &outY);

// Unsharp mask with a 3x3 binomial blur.
void sharpen(GrayF &y, float amount);

}  // namespace mf
