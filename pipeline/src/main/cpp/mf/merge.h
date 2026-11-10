/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <vector>

#include "align.h"
#include "image.h"

namespace mf {

struct MergeParams {
    // A pixel of an alternate frame gets full weight while its local difference to the reference
    // is below lowSigma * noise, and zero weight above highSigma * noise.
    float lowSigma = 1.5f;
    float highSigma = 4.0f;
};

struct MergeStats {
    float noiseSigma = 0;  // estimated per-pixel noise of the half-resolution reference luma
    float meanWeight = 0;  // average weight given to alternate frames (1 = fully merged)
};

// Robust temporal merge in overlapping raised-cosine tiles (32x32 luma, stride 16).
// outY is width x height; outVU is interleaved V,U, width x height/2.
void mergeFrames(const std::vector<const Frame *> &frames, const std::vector<const Gray *> &half,
                 int ref, const std::vector<std::vector<Displacement>> &disp, const TileGrid &grid,
                 const MergeParams &params, GrayF &outY, GrayF &outVU, MergeStats *stats);

// Robust noise sigma of a gray image from the median absolute second difference.
float estimateNoise(const Gray &g);

}  // namespace mf
