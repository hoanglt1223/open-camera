/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <string>
#include <vector>

#include "image.h"

namespace mf {

// Values are shared with the Kotlin facade (StackOp.nativeId).
enum class StackOp : int {
    Mean = 0,    // simulated long exposure: aligned average
    Max = 1,     // light trails: per-pixel brightest frame (lighten blend)
    Median = 2,  // passer-by removal: per-pixel median, drops anything that moves
    Focus = 3,   // focus stacking: per-pixel weights from local sharpness
    Moon = 4,    // lucky imaging: keep the sharpest frames, average, mild deconvolution
};

struct StackParams {
    StackOp op = StackOp::Mean;
    int reference = -1;          // -1 picks one per op
    bool align = true;           // false for tripod shots where frames must not move
    float keepFraction = 0.4f;   // Moon: share of sharpest frames kept
    int rlIterations = 8;        // Moon: Richardson-Lucy iterations (clamped to 0..15)
    float rlSigma = 1.2f;        // Moon: Gaussian PSF sigma, full-resolution pixels
    float sharpen = 0.5f;        // Moon: unsharp mask amount after deconvolution
    float strength = 1.0f;       // Moon: blend of the restored image over the plain stack
};

struct StackResult {
    Frame image;
    int referenceIndex = 0;
    int framesUsed = 0;
    double ms = 0;
};

// Aligns the burst (phase 4 tile aligner) and combines it with params.op. Returns false with a
// reason for unusable input or out-of-memory; callers then fall back to a single frame.
bool stackBurst(const std::vector<Frame> &frames, const StackParams &params, StackResult &result,
                std::string *error);

// Frame indices sorted from sharpest to softest (gradient energy of the half-resolution luma).
std::vector<int> rankBySharpness(const std::vector<Frame> &frames);

// In-place Richardson-Lucy deconvolution with a Gaussian PSF; the per-iteration correction is
// clamped to limit ringing. Exposed for tests.
void richardsonLucy(GrayF &img, float sigma, int iterations);

void gaussianBlur(const GrayF &src, GrayF &dst, float sigma);

}  // namespace mf
