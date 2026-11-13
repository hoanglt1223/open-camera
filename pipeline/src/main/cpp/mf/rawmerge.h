/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "align.h"
#include "image.h"
#include "raw.h"

namespace mf {

struct RawMergeParams {
    Cfa cfa = Cfa::Rggb;
    float white = 1023;
    // Optional lens shading gains, lscW x lscH x 4 (R, G_even, G_odd, B), applied once after merging.
    std::vector<float> lsc;
    int lscW = 0;
    int lscH = 0;
    // A tile of an alternate frame gets full weight while its mean absolute difference to the
    // reference is below lowSigma * noise and zero weight above highSigma * noise.
    float lowSigma = 1.4f;
    float highSigma = 2.6f;
};

// Streaming temporal merge of RAW16 Bayer frames. The first frame is the reference; every later
// frame is aligned to it on a 2x2-binned luma (whole CFA blocks, so the Bayer phase is kept),
// weighted per tile by its difference to the reference and accumulated. Memory does not grow with
// the frame count: ~5 bytes per pixel plus the reference pyramid.
class RawMerger {
public:
    // black: per CFA position in reading order. Returns false (with a reason) for unusable input.
    bool begin(int width, int height, const RawMergeParams &params, const uint16_t *raw,
               int rowStrideBytes, const float black[4], std::string *error);
    // Aligns and accumulates one more frame; weight receives its mean tile weight (0..1).
    bool add(const uint16_t *raw, int rowStrideBytes, const float black[4], float *weight,
             std::string *error);
    // Normalises the sum into a 16-bit linear mosaic (black 0, white 65535, shading corrected).
    bool finish(std::string *error);

    // Bilinear demosaic of rows [y0, y0 + rows) of the finished mosaic into interleaved camera RGB.
    bool demosaicRows(int y0, int rows, uint16_t *out) const;

    int width() const { return width_; }
    int height() const { return height_; }
    int frames() const { return frames_; }
    float noiseSigma() const { return noise_; }
    const std::vector<uint16_t> &mosaic() const { return mosaic_; }
    Cfa cfa() const { return params_.cfa; }

private:
    Gray binnedLuma(const uint16_t *raw, int rowStrideBytes, const float black[4]) const;
    void accumulate(const uint16_t *raw, int rowStrideBytes, const float black[4],
                    const std::vector<Displacement> *disp, const std::vector<float> *tileWeights);

    int width_ = 0;
    int height_ = 0;
    int frames_ = 0;
    float noise_ = 0;
    float refBlack_[4] = {0, 0, 0, 0};
    RawMergeParams params_;
    TileGrid grid_;
    Pyramid ref_;
    std::vector<float> sum_;     // width x height, weighted sum of black-subtracted samples
    std::vector<float> weight_;  // (width / 2) x (height / 2), per 2x2 block
    std::vector<uint16_t> mosaic_;
};

}  // namespace mf
