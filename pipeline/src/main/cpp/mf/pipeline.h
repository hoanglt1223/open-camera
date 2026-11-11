/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <functional>
#include <string>
#include <vector>

#include "image.h"

namespace mf {

// Values are shared with the Kotlin facade (MultiFrameMode.nativeId).
enum class Mode : int { Hdr = 0, Night = 1, Astro = 2, SuperRes = 3 };

struct Params {
    Mode mode = Mode::Hdr;
    // Spacing of synthetic exposures for local tone mapping, in stops. Usually the amount the
    // burst was underexposed by. <= 0 disables tone mapping.
    float toneEvStep = 1.0f;
    // Reference frame index, or -1 to pick the sharpest of the first three frames.
    int reference = -1;
};

struct Result {
    Frame image;
    int referenceIndex = 0;
    float noiseSigma = 0;
    float meanWeight = 0;
    double msAlign = 0;
    double msMerge = 0;
    double msFinish = 0;
};

// Aligns and merges a burst into one frame. Returns false (with a reason) when the input is not
// usable or memory runs out; callers then fall back to a single frame.
bool processBurst(const std::vector<Frame> &frames, const Params &params, Result &result,
                  const std::function<void(float)> &progress, std::string *error);

}  // namespace mf
