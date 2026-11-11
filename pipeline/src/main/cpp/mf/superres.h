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

// True multi-frame super-resolution of an unzoomed crop. [full] holds the full-resolution luma of
// each frame's crop (native sensor samples, no HAL upscale); [disp] are the alignment
// displacements on the half-resolution grid [grid] of the same crops. Samples from all frames are
// gathered onto a grid [scale] times finer, of size outW x outH.
void superResolveCrop(const std::vector<const Gray *> &full, int ref,
                      const std::vector<std::vector<Displacement>> &disp, const TileGrid &grid,
                      float noiseSigma, float scale, int outW, int outH, GrayF &outY);

// Bilinear resize of a plane (also used for interleaved VU with [channels] = 2).
void resizeBilinear(const GrayF &src, int channels, int outW, int outH, GrayF &out);

// Unsharp mask with a 3x3 binomial blur.
void sharpen(GrayF &y, float amount);

}  // namespace mf
