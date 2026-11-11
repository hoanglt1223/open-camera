// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// Seam removal for the healing brush (Perez, Gangnet and Blake, "Poisson Image Editing",
// 2003, in its membrane form): the fill keeps its own gradients and receives a smooth offset
// that matches the surrounding skin at the boundary.

#include <algorithm>
#include <cstring>
#include <vector>

#include "retouch_core.h"

namespace retouch {

void healSeams(const Image& img, const Image& original, const uint8_t* mask) {
    const int w = img.w, h = img.h;
    if (mask == nullptr || original.w != w || original.h != h) return;
    constexpr int kRing = 2;
    // inner = mask eroded by kRing (Chebyshev); ring = mask minus inner.
    std::vector<uint8_t> inner(size_t(w) * h, 0);
    int x0 = w, y0 = h, x1 = -1, y1 = -1;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (!mask[size_t(y) * w + x]) continue;
            x0 = std::min(x0, x);
            y0 = std::min(y0, y);
            x1 = std::max(x1, x);
            y1 = std::max(y1, y);
            bool all = true;
            for (int dy = -kRing; dy <= kRing && all; ++dy) {
                for (int dx = -kRing; dx <= kRing; ++dx) {
                    int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h || !mask[size_t(yy) * w + xx]) {
                        all = false;
                        break;
                    }
                }
            }
            inner[size_t(y) * w + x] = all ? 1 : 0;
        }
    }
    if (x1 < 0) return;
    const int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
    // 0 = outside, 1 = fixed (ring), 2 = unknown (inner).
    std::vector<uint8_t> kind(size_t(bw) * bh, 0);
    std::vector<float> d(size_t(bw) * bh * 3, 0.f);
    for (int y = 0; y < bh; ++y) {
        const uint8_t* fr = img.row(y + y0);
        const uint8_t* orow = original.row(y + y0);
        for (int x = 0; x < bw; ++x) {
            size_t g = size_t(y + y0) * w + x + x0;
            size_t i = size_t(y) * bw + x;
            if (!mask[g]) continue;
            if (inner[g]) {
                kind[i] = 2;
            } else {
                kind[i] = 1;
                for (int ch = 0; ch < 3; ++ch) {
                    d[i * 3 + ch] = float(orow[(x + x0) * 4 + ch]) - float(fr[(x + x0) * 4 + ch]);
                }
            }
        }
    }
    // Successive over-relaxation of the Laplace equation over the inner pixels; neighbours
    // outside the mask do not exist for the membrane (Neumann), which keeps it stable.
    const int iterations = std::clamp(std::max(bw, bh) * 2, 60, 400);
    constexpr float kOmega = 1.85f;
    for (int it = 0; it < iterations; ++it) {
        for (int y = 0; y < bh; ++y) {
            for (int x = 0; x < bw; ++x) {
                size_t i = size_t(y) * bw + x;
                if (kind[i] != 2) continue;
                float acc[3] = {0, 0, 0};
                int n = 0;
                const int nx[4] = {x - 1, x + 1, x, x};
                const int ny[4] = {y, y, y - 1, y + 1};
                for (int k = 0; k < 4; ++k) {
                    if (nx[k] < 0 || ny[k] < 0 || nx[k] >= bw || ny[k] >= bh) continue;
                    size_t j = size_t(ny[k]) * bw + nx[k];
                    if (kind[j] == 0) continue;
                    for (int ch = 0; ch < 3; ++ch) acc[ch] += d[j * 3 + ch];
                    ++n;
                }
                if (n == 0) continue;
                for (int ch = 0; ch < 3; ++ch) {
                    float& v = d[i * 3 + ch];
                    v += kOmega * (acc[ch] / float(n) - v);
                }
            }
        }
    }
    for (int y = 0; y < bh; ++y) {
        uint8_t* fr = img.row(y + y0);
        const uint8_t* orow = original.row(y + y0);
        for (int x = 0; x < bw; ++x) {
            size_t i = size_t(y) * bw + x;
            if (kind[i] == 0) continue;
            uint8_t* p = fr + (x + x0) * 4;
            if (kind[i] == 1) {
                std::memcpy(p, orow + (x + x0) * 4, 3);
                continue;
            }
            for (int ch = 0; ch < 3; ++ch) {
                p[ch] = uint8_t(std::clamp(float(p[ch]) + d[i * 3 + ch], 0.f, 255.f) + 0.5f);
            }
        }
    }
}

}  // namespace retouch
