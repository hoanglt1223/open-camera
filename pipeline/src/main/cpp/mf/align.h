/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <vector>

#include "image.h"

namespace mf {

// Tile grid on the finest alignment level. Tile k spans [origin + k*stride, origin + k*stride + size).
struct TileGrid {
    int size = 16;
    int stride = 8;
    int origin = -8;
    int cols = 0;
    int rows = 0;

    static TileGrid cover(int width, int height, int size = 16) {
        TileGrid g;
        g.size = size;
        g.stride = size / 2;
        g.origin = -size / 2;
        g.cols = (width - g.origin + g.stride - 1) / g.stride;
        g.rows = (height - g.origin + g.stride - 1) / g.stride;
        return g;
    }
};

struct Displacement {
    float dx = 0;
    float dy = 0;
};

// Gaussian-like box pyramid. Level 0 is the input; then factors 2, 4, 4.
struct Pyramid {
    static constexpr int kLevels = 4;
    static constexpr int kFactor[kLevels] = {1, 2, 4, 4};
    Gray level[kLevels];
};

Gray downsampleBox(const Gray &src, int factor);
Gray halfResLuma(const Frame &frame);
Pyramid buildPyramid(Gray level0);

// Coarse-to-fine tile alignment of alt onto ref. Displacements are in level-0 pixels and point
// from a ref tile to the matching alt tile (alt(x + d) ~ ref(x)). Sub-pixel refinement by
// quadratic fit of the L2 cost on the finest level.
std::vector<Displacement> alignTiles(const Pyramid &ref, const Pyramid &alt, const TileGrid &grid);

// Sum of squared gradients on a gray image; used to pick the sharpest reference frame.
double sharpness(const Gray &g);

}  // namespace mf
