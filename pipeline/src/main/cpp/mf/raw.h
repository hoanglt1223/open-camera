/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cstdint>

#include "image.h"

namespace mf {

// Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT values (RGB Bayer layouts only).
enum class Cfa : int { Rggb = 0, Grbg = 1, Gbrg = 2, Bggr = 3 };

// Development parameters, all taken from Camera2 metadata of the frame.
struct RawParams {
    Cfa cfa = Cfa::Rggb;
    // Black level per CFA position in reading order (top-left, top-right, bottom-left,
    // bottom-right of each 2x2 block), as SENSOR_DYNAMIC_BLACK_LEVEL / SENSOR_BLACK_LEVEL_PATTERN.
    float black[4] = {64, 64, 64, 64};
    float white = 1023;
    // COLOR_CORRECTION_GAINS order: R, G_even, G_odd, B.
    float wb[4] = {1, 1, 1, 1};
    // Row-major 3x3 from white-balanced camera RGB to linear sRGB (COLOR_CORRECTION_TRANSFORM).
    float ccm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    // Optional lens shading gains, lscW x lscH x 4 (R, G_even, G_odd, B) over the whole frame.
    const float *lsc = nullptr;
    int lscW = 0;
    int lscH = 0;
    // Extra linear gain applied before the tone curve (exposure compensation of the burst).
    float gain = 1.0f;
};

// Develops a RAW16 Bayer frame (rowStride in bytes) into an NV21 frame of the same size:
// black/white levels, lens shading, white balance, bilinear demosaic, colour matrix, sRGB tone
// curve, BT.601 full-range YCbCr. Returns false for unsupported input.
bool developRaw(const uint16_t *raw, int width, int height, int rowStrideBytes, const RawParams &params,
                Frame &out);

}  // namespace mf
