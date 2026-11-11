/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include "image.h"

namespace mf {

struct ToneParams {
    float evStep = 1.0f;     // spacing of synthetic exposures in stops; <= 0 disables tone mapping
    int exposures = 3;       // synthetic exposures: +0, +evStep, +2*evStep, ...
    float maxGain = 6.0f;    // cap on local brightening
};

// Local tone mapping: Mertens-style exposure fusion of synthetic exposures on a 1/8 resolution
// copy, applied to full resolution as an edge-aware (joint bilateral) upsampled gain map.
void localToneMap(GrayF &y, GrayF &vu, const ToneParams &params);

// Cross-bilateral 5x5 chroma smoothing; strength scales the range threshold (0 disables).
void chromaDenoise(GrayF &vu, float strength);

// Replaces isolated single-pixel spikes (hot pixels) by their brightest neighbour.
void suppressHotPixels(GrayF &y, float threshold);

void toFrame(const GrayF &y, const GrayF &vu, Frame &out);

}  // namespace mf
