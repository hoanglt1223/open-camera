// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// Skin smoothing by frequency separation. The image splits into three bands:
//   base  = guided filter (edge-aware, He et al. 2010) with luma as guide,
//   mid   = box(rf) - base   (blotches, redness, small bumps),
//   fine  = image - box(rf)  (pores and fine texture).
// Smoothing attenuates mid by [strength] and fine only slightly, so texture survives.

#include <algorithm>
#include <atomic>
#include <new>
#include <vector>

#include "../mf/parallel.h"
#include "retouch_core.h"

namespace retouch {

void smoothSkin(const Image& img, const MaskView& mask, float strength, int radius) {
    if (strength <= 0.f || radius < 1) return;
    strength = std::clamp(strength, 0.f, 1.f);
    const int w = img.w, h = img.h;
    int x0, y0, x1, y1;
    if (!mask.bounds(w, h, x0, y0, x1, y1)) return;
    // Pad so the filters see context around the mask edge.
    x0 = std::max(0, x0 - radius);
    y0 = std::max(0, y0 - radius);
    x1 = std::min(w - 1, x1 + radius);
    y1 = std::min(h - 1, y1 + radius);
    const int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
    const size_t n = size_t(bw) * bh;
    std::vector<float> luma(n);
    std::vector<float> out(n * 3);
    for (int y = 0; y < bh; ++y) {
        const uint8_t* row = img.row(y + y0) + x0 * 4;
        for (int x = 0; x < bw; ++x) {
            const uint8_t* p = row + x * 4;
            luma[size_t(y) * bw + x] = (0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]) / 255.f;
        }
    }
    const int rf = std::max(1, radius / 8);
    const float eps = 0.0025f;
    const float kMid = strength;
    const float kFine = 0.25f * strength;
    // Channels are independent: one thread each. Exceptions must not leave a worker thread.
    std::atomic<bool> failed{false};
    mf::parallelFor(0, 3, [&](int c) {
      try {
        std::vector<float> ch(n), base(n), box(n), baseBox(n);
        for (int y = 0; y < bh; ++y) {
            const uint8_t* row = img.row(y + y0) + x0 * 4;
            for (int x = 0; x < bw; ++x) ch[size_t(y) * bw + x] = row[x * 4 + c] / 255.f;
        }
        aicore::guidedFilter(luma.data(), ch.data(), base.data(), bw, bh, radius, eps);
        aicore::boxMean(ch.data(), box.data(), bw, bh, rf);
        // Low-pass the base too, so fine edges the guided filter kept stay in the fine band.
        aicore::boxMean(base.data(), baseBox.data(), bw, bh, rf);
        for (size_t i = 0; i < n; ++i) {
            float mid = box[i] - baseBox[i];
            float fine = ch[i] - box[i];
            out[i * 3 + c] = kMid * mid + kFine * fine;
        }
      } catch (...) {
        failed = true;
      }
    });
    if (failed) throw std::bad_alloc();    for (int y = 0; y < bh; ++y) {
        uint8_t* row = img.row(y + y0) + x0 * 4;
        for (int x = 0; x < bw; ++x) {
            float a = std::clamp(mask.at(x + x0, y + y0), 0.f, 1.f);
            if (a <= 0.f) continue;
            size_t i = size_t(y) * bw + x;
            for (int c = 0; c < 3; ++c) {
                float v = row[x * 4 + c] - 255.f * a * out[i * 3 + c];
                row[x * 4 + c] = uint8_t(std::clamp(v, 0.f, 255.f) + 0.5f);
            }
        }
    }
}

}  // namespace retouch
