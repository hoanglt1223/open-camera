/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

namespace mf {

// Full-resolution 4:2:0 frame. vu is interleaved V,U (NV21 order) at half resolution.
struct Frame {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> y;
    std::vector<uint8_t> vu;

    Frame() = default;
    Frame(int w, int h) : width(w), height(h), y(size_t(w) * h), vu(size_t(w / 2) * (h / 2) * 2) {}
};

// Single channel 8-bit image.
struct Gray {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> px;

    Gray() = default;
    Gray(int w, int h) : width(w), height(h), px(size_t(w) * h) {}

    uint8_t at(int x, int y) const {
        x = std::clamp(x, 0, width - 1);
        y = std::clamp(y, 0, height - 1);
        return px[size_t(y) * width + x];
    }
};

struct GrayF {
    int width = 0;
    int height = 0;
    std::vector<float> px;

    GrayF() = default;
    GrayF(int w, int h) : width(w), height(h), px(size_t(w) * h) {}

    float at(int x, int y) const {
        x = std::clamp(x, 0, width - 1);
        y = std::clamp(y, 0, height - 1);
        return px[size_t(y) * width + x];
    }
};

inline uint8_t clampU8(float v) {
    return uint8_t(std::clamp(v + 0.5f, 0.0f, 255.0f));
}

}  // namespace mf
