/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "merge.h"

#include <array>
#include <atomic>
#include <cmath>

#include "parallel.h"

namespace mf {

float estimateNoise(const Gray &g) {
    std::array<uint32_t, 1021> hist{};
    uint64_t n = 0;
    for (int y = 0; y < g.height; y += 2) {
        const uint8_t *row = &g.px[size_t(y) * g.width];
        for (int x = 1; x + 1 < g.width; ++x) {
            int d = 2 * int(row[x]) - row[x - 1] - row[x + 1];
            hist[size_t(std::abs(d))]++;
            ++n;
        }
    }
    if (n == 0) return 1;
    uint64_t half = n / 2, acc = 0;
    size_t median = 0;
    for (; median < hist.size(); ++median) {
        acc += hist[median];
        if (acc >= half) break;
    }
    // d2 = 2p - l - r has variance 6 sigma^2; median |N(0,1)| = 0.6745.
    float sigma = float(median) / (0.6745f * std::sqrt(6.0f));
    return std::max(sigma, 0.5f);
}

namespace {

constexpr int kHalfTile = 16;  // tile size on the half-resolution grid
constexpr int kTile = 32;      // tile size on the full-resolution luma grid

struct Windows {
    float luma[kTile];
    float chroma[kHalfTile];
    Windows() {
        for (int k = 0; k < kTile; ++k) luma[k] = 0.5f - 0.5f * std::cos(2 * M_PI * (k + 0.5) / kTile);
        for (int k = 0; k < kHalfTile; ++k) {
            chroma[k] = 0.5f - 0.5f * std::cos(2 * M_PI * (k + 0.5) / kHalfTile);
        }
    }
};

struct TileAccum {
    float y[kTile][kTile];
    float vu[kHalfTile][kTile];
    float w[kHalfTile][kHalfTile];
};

}  // namespace

void mergeFrames(const std::vector<const Frame *> &frames, const std::vector<const Gray *> &half,
                 int ref, const std::vector<std::vector<Displacement>> &disp, const TileGrid &grid,
                 const MergeParams &params, GrayF &outY, GrayF &outVU, MergeStats *stats) {
    const Frame &rf = *frames[ref];
    const Gray &rh = *half[ref];
    const int W = rf.width, H = rf.height;
    const int hw = W / 2, hh = H / 2;
    outY = GrayF(W, H);
    outVU = GrayF(W, hh);
    std::fill(outY.px.begin(), outY.px.end(), 0.0f);
    std::fill(outVU.px.begin(), outVU.px.end(), 0.0f);

    const float sigma = estimateNoise(rh);
    const float t1 = params.lowSigma * sigma + 2.0f;
    const float t2 = std::max(params.highSigma * sigma + 4.0f, t1 + 1.0f);
    const float invRange = 1.0f / (t2 - t1);
    static const Windows win;

    std::atomic<uint64_t> weightSum{0};
    std::atomic<uint64_t> weightCount{0};

    auto processTile = [&](int r, int c, TileAccum &acc) {
        const int hx0 = grid.origin + c * grid.stride;
        const int hy0 = grid.origin + r * grid.stride;
        const int fx0 = 2 * hx0, fy0 = 2 * hy0;

        for (int j = 0; j < kTile; ++j) {
            const int yy = std::clamp(fy0 + j, 0, H - 1);
            for (int i = 0; i < kTile; ++i) acc.y[j][i] = rf.y[size_t(yy) * W + std::clamp(fx0 + i, 0, W - 1)];
        }
        for (int j = 0; j < kHalfTile; ++j) {
            const int yy = std::clamp(hy0 + j, 0, hh - 1);
            for (int i = 0; i < kHalfTile; ++i) {
                const size_t o = size_t(yy) * W + 2 * std::clamp(hx0 + i, 0, hw - 1);
                acc.vu[j][2 * i] = rf.vu[o];
                acc.vu[j][2 * i + 1] = rf.vu[o + 1];
                acc.w[j][i] = 1.0f;
            }
        }

        uint64_t localW = 0, localN = 0;
        const size_t tileIndex = size_t(r) * grid.cols + c;
        for (size_t f = 0; f < frames.size(); ++f) {
            if (int(f) == ref) continue;
            const Frame &af = *frames[f];
            const Gray &ah = *half[f];
            const Displacement d = disp[f][tileIndex];
            const int hdx = int(std::lround(d.dx)), hdy = int(std::lround(d.dy));
            const int ldx = int(std::lround(2 * d.dx)), ldy = int(std::lround(2 * d.dy));

            float diff[kHalfTile + 2][kHalfTile + 2];
            for (int j = 0; j < kHalfTile + 2; ++j) {
                for (int i = 0; i < kHalfTile + 2; ++i) {
                    const int x = hx0 + i - 1, y = hy0 + j - 1;
                    diff[j][i] = std::fabs(float(rh.at(x, y)) - float(ah.at(x + hdx, y + hdy)));
                }
            }
            float w[kHalfTile][kHalfTile];
            for (int j = 0; j < kHalfTile; ++j) {
                for (int i = 0; i < kHalfTile; ++i) {
                    float s = 0;
                    for (int b = 0; b < 3; ++b) s += diff[j + b][i] + diff[j + b][i + 1] + diff[j + b][i + 2];
                    w[j][i] = std::clamp((t2 - s * (1.0f / 9.0f)) * invRange, 0.0f, 1.0f);
                    acc.w[j][i] += w[j][i];
                    localW += uint64_t(w[j][i] * 255.0f);
                }
            }
            localN += kHalfTile * kHalfTile;

            const bool lumaInside = fx0 + ldx >= 0 && fy0 + ldy >= 0 && fx0 + ldx + kTile <= W &&
                                    fy0 + ldy + kTile <= H;
            for (int j = 0; j < kTile; ++j) {
                const float *wr = w[j / 2];
                if (lumaInside) {
                    const uint8_t *src = &af.y[size_t(fy0 + ldy + j) * W + fx0 + ldx];
                    for (int i = 0; i < kTile; ++i) acc.y[j][i] += wr[i / 2] * src[i];
                } else {
                    const int yy = std::clamp(fy0 + ldy + j, 0, H - 1);
                    for (int i = 0; i < kTile; ++i) {
                        acc.y[j][i] += wr[i / 2] * af.y[size_t(yy) * W + std::clamp(fx0 + ldx + i, 0, W - 1)];
                    }
                }
            }
            for (int j = 0; j < kHalfTile; ++j) {
                const int yy = std::clamp(hy0 + hdy + j, 0, hh - 1);
                for (int i = 0; i < kHalfTile; ++i) {
                    const size_t o = size_t(yy) * W + 2 * std::clamp(hx0 + hdx + i, 0, hw - 1);
                    acc.vu[j][2 * i] += w[j][i] * af.vu[o];
                    acc.vu[j][2 * i + 1] += w[j][i] * af.vu[o + 1];
                }
            }
        }
        weightSum += localW;
        weightCount += localN;

        for (int j = 0; j < kTile; ++j) {
            const int yy = fy0 + j;
            if (yy < 0 || yy >= H) continue;
            float *dst = &outY.px[size_t(yy) * W];
            for (int i = 0; i < kTile; ++i) {
                const int xx = fx0 + i;
                if (xx < 0 || xx >= W) continue;
                dst[xx] += win.luma[j] * win.luma[i] * acc.y[j][i] / acc.w[j / 2][i / 2];
            }
        }
        for (int j = 0; j < kHalfTile; ++j) {
            const int yy = hy0 + j;
            if (yy < 0 || yy >= hh) continue;
            float *dst = &outVU.px[size_t(yy) * W];
            for (int i = 0; i < kHalfTile; ++i) {
                const int xx = hx0 + i;
                if (xx < 0 || xx >= hw) continue;
                const float k = win.chroma[j] * win.chroma[i] / acc.w[j][i];
                dst[2 * xx] += k * acc.vu[j][2 * i];
                dst[2 * xx + 1] += k * acc.vu[j][2 * i + 1];
            }
        }
    };

    // Tiles overlap by half, so rows of the same parity never write the same pixels.
    for (int parity = 0; parity < 2; ++parity) {
        parallelFor(0, (grid.rows - parity + 1) / 2, [&](int k) {
            TileAccum acc;
            const int r = 2 * k + parity;
            for (int c = 0; c < grid.cols; ++c) processTile(r, c, acc);
        });
    }

    if (stats) {
        stats->noiseSigma = sigma;
        const uint64_t n = weightCount.load();
        stats->meanWeight = n ? float(weightSum.load()) / (255.0f * n) : 1.0f;
    }
}

}  // namespace mf
