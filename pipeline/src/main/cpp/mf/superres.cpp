/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "superres.h"

#include <cmath>

#include "parallel.h"

namespace mf {

namespace {

constexpr float kSigma = 0.55f;  // kernel sigma in half-resolution pixels
constexpr int kLutSize = 256;
constexpr float kLutMaxDist2 = 8.0f;

int tileFor(float pos, const TileGrid &g, int count) {
    return std::clamp(int(std::lround((pos - g.origin - g.size * 0.5f) / g.stride)), 0, count - 1);
}

}  // namespace

void superResolveLuma(const std::vector<const Gray *> &half, int ref,
                      const std::vector<std::vector<Displacement>> &disp, const TileGrid &grid,
                      float noiseSigma, GrayF &outY) {
    const Gray &rh = *half[ref];
    const int hw = rh.width, hh = rh.height;
    const int W = hw * 2, H = hh * 2;
    outY = GrayF(W, H);

    float kernel[kLutSize];
    for (int i = 0; i < kLutSize; ++i) {
        const float d2 = i * kLutMaxDist2 / kLutSize;
        kernel[i] = std::exp(-d2 / (2 * kSigma * kSigma));
    }

    // Robustness per alternate frame and half-res sample, against the reference at the
    // integer-displaced position.
    const float t1 = 2.5f * noiseSigma + 3.0f;
    const float t2 = 6.0f * noiseSigma + 8.0f;
    std::vector<std::vector<uint8_t>> robust(half.size());
    for (size_t f = 0; f < half.size(); ++f) {
        if (int(f) == ref) continue;
        const Gray &ah = *half[f];
        robust[f].resize(size_t(hw) * hh);
        parallelFor(0, hh, [&](int y) {
            for (int x = 0; x < hw; ++x) {
                const Displacement d = disp[f][size_t(tileFor(y, grid, grid.rows)) * grid.cols +
                                               tileFor(x, grid, grid.cols)];
                const float diff = std::fabs(float(ah.px[size_t(y) * hw + x]) -
                                             float(rh.at(x - int(std::lround(d.dx)), y - int(std::lround(d.dy)))));
                robust[f][size_t(y) * hw + x] =
                    uint8_t(255.0f * std::clamp((t2 - diff) / (t2 - t1), 0.0f, 1.0f));
            }
        });
    }

    parallelFor(0, H, [&](int oy) {
        const float qy = (oy + 0.5f) * 0.5f - 0.5f;
        const int tr = tileFor(qy, grid, grid.rows);
        float *dst = &outY.px[size_t(oy) * W];
        for (int ox = 0; ox < W; ++ox) {
            const float qx = (ox + 0.5f) * 0.5f - 0.5f;
            const size_t tile = size_t(tr) * grid.cols + tileFor(qx, grid, grid.cols);
            float sum = 0, wsum = 0;
            for (size_t f = 0; f < half.size(); ++f) {
                const Gray &ah = *half[f];
                const bool isRef = int(f) == ref;
                const float dx = isRef ? 0 : disp[f][tile].dx;
                const float dy = isRef ? 0 : disp[f][tile].dy;
                const float px = qx + dx, py = qy + dy;
                const int cx = int(std::lround(px)), cy = int(std::lround(py));
                for (int j = -1; j <= 1; ++j) {
                    const int sy = cy + j;
                    if (sy < 0 || sy >= hh) continue;
                    const float ey = sy - py;
                    for (int i = -1; i <= 1; ++i) {
                        const int sx = cx + i;
                        if (sx < 0 || sx >= hw) continue;
                        const float ex = sx - px;
                        const int k = std::min(kLutSize - 1, int((ex * ex + ey * ey) * (kLutSize / kLutMaxDist2)));
                        float w = kernel[k];
                        const size_t o = size_t(sy) * hw + sx;
                        if (!isRef) w *= robust[f][o] * (1.0f / 255.0f);
                        sum += w * ah.px[o];
                        wsum += w;
                    }
                }
            }
            dst[ox] = wsum > 1e-4f ? sum / wsum : float(rh.at(int(qx + 0.5f), int(qy + 0.5f)));
        }
    });
}

void sharpen(GrayF &y, float amount) {
    if (amount <= 0) return;
    const GrayF src = y;
    parallelFor(0, y.height, [&](int r) {
        for (int c = 0; c < y.width; ++c) {
            float blur = 0;
            for (int j = -1; j <= 1; ++j) {
                for (int i = -1; i <= 1; ++i) blur += (2 - std::abs(i)) * (2 - std::abs(j)) * src.at(c + i, r + j);
            }
            blur *= 1.0f / 16.0f;
            const float p = src.px[size_t(r) * y.width + c];
            y.px[size_t(r) * y.width + c] = std::clamp(p + amount * (p - blur), 0.0f, 255.0f);
        }
    });
}

}  // namespace mf
