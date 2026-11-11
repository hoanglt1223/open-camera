/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "superres.h"

#include <cmath>

#include "parallel.h"

namespace mf {

namespace {

constexpr float kSigma = 0.3f;  // kernel sigma in half-resolution pixels
constexpr int kLutSize = 256;
constexpr float kLutMaxDist2 = 8.0f;

int tileFor(float pos, const TileGrid &g, int count) {
    return std::clamp(int(std::lround((float(pos) - g.origin - g.size * 0.5f) / g.stride)), 0, count - 1);
}

float bilinear(const Gray &g, float x, float y) {
    const int x0 = int(std::floor(x)), y0 = int(std::floor(y));
    const float fx = x - x0, fy = y - y0;
    const float top = g.at(x0, y0) * (1 - fx) + g.at(x0 + 1, y0) * fx;
    const float bot = g.at(x0, y0 + 1) * (1 - fx) + g.at(x0 + 1, y0 + 1) * fx;
    return top * (1 - fy) + bot * fy;
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

    // Robustness per alternate frame and half-res sample, against the reference interpolated at
    // the sub-pixel displaced position.
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
                const float diff = std::fabs(float(ah.px[size_t(y) * hw + x]) - bilinear(rh, x - d.dx, y - d.dy));
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

void superResolveCrop(const std::vector<const Gray *> &full, int ref,
                      const std::vector<std::vector<Displacement>> &disp, const TileGrid &grid,
                      float noiseSigma, float scale, int outW, int outH, GrayF &outY) {
    const Gray &rf = *full[ref];
    const int fw = rf.width, fh = rf.height;
    outY = GrayF(outW, outH);
    // Kernel width in input pixels, tuned on the synthetic 2x test (0.3-0.4 is best; wider blurs
    // away the recovered detail, narrower leaves holes between samples).
    const float sigma = 0.35f;
    float kernel[kLutSize];
    for (int i = 0; i < kLutSize; ++i) {
        const float d2 = i * kLutMaxDist2 / kLutSize;
        kernel[i] = std::exp(-d2 / (2 * sigma * sigma));
    }
    // Full-resolution noise is about twice the half-resolution (2x2 mean) estimate.
    const float s = 2.0f * noiseSigma;
    const float t1 = 2.5f * s + 3.0f;
    const float t2 = 6.0f * s + 8.0f;
    std::vector<std::vector<uint8_t>> robust(full.size());
    for (size_t f = 0; f < full.size(); ++f) {
        if (int(f) == ref) continue;
        const Gray &af = *full[f];
        robust[f].resize(size_t(fw) * fh);
        parallelFor(0, fh, [&](int y) {
            const int tr = tileFor(y * 0.5f, grid, grid.rows);
            for (int x = 0; x < fw; ++x) {
                const Displacement d = disp[f][size_t(tr) * grid.cols + tileFor(x * 0.5f, grid, grid.cols)];
                const float diff =
                    std::fabs(float(af.px[size_t(y) * fw + x]) - bilinear(rf, x - 2 * d.dx, y - 2 * d.dy));
                robust[f][size_t(y) * fw + x] = uint8_t(255.0f * std::clamp((t2 - diff) / (t2 - t1), 0.0f, 1.0f));
            }
        });
    }

    const float inv = 1.0f / scale;
    parallelFor(0, outH, [&](int oy) {
        const float qy = (oy + 0.5f) * inv - 0.5f;
        const int tr = tileFor(qy * 0.5f, grid, grid.rows);
        float *dst = &outY.px[size_t(oy) * outW];
        for (int ox = 0; ox < outW; ++ox) {
            const float qx = (ox + 0.5f) * inv - 0.5f;
            const size_t tile = size_t(tr) * grid.cols + tileFor(qx * 0.5f, grid, grid.cols);
            float sum = 0, wsum = 0;
            for (size_t f = 0; f < full.size(); ++f) {
                const Gray &af = *full[f];
                const bool isRef = int(f) == ref;
                const float px = qx + (isRef ? 0 : 2 * disp[f][tile].dx);
                const float py = qy + (isRef ? 0 : 2 * disp[f][tile].dy);
                const int cx = int(std::lround(px)), cy = int(std::lround(py));
                for (int j = -1; j <= 1; ++j) {
                    const int sy = cy + j;
                    if (sy < 0 || sy >= fh) continue;
                    const float ey = sy - py;
                    for (int i = -1; i <= 1; ++i) {
                        const int sx = cx + i;
                        if (sx < 0 || sx >= fw) continue;
                        const float ex = sx - px;
                        const int k = std::min(kLutSize - 1, int((ex * ex + ey * ey) * (kLutSize / kLutMaxDist2)));
                        float w = kernel[k];
                        const size_t o = size_t(sy) * fw + sx;
                        if (!isRef) w *= robust[f][o] * (1.0f / 255.0f);
                        sum += w * af.px[o];
                        wsum += w;
                    }
                }
            }
            dst[ox] = wsum > 1e-3f ? sum / wsum : bilinear(rf, qx, qy);
        }
    });
}

void resizeBilinear(const GrayF &src, int channels, int outW, int outH, GrayF &out) {
    const int sw = src.width / channels, sh = src.height;
    out = GrayF(outW * channels, outH);
    const float sx = float(sw) / outW, sy = float(sh) / outH;
    parallelFor(0, outH, [&](int y) {
        const float v = std::clamp((y + 0.5f) * sy - 0.5f, 0.0f, float(sh - 1));
        const int y0 = int(v), y1 = std::min(y0 + 1, sh - 1);
        const float fy = v - y0;
        for (int x = 0; x < outW; ++x) {
            const float u = std::clamp((x + 0.5f) * sx - 0.5f, 0.0f, float(sw - 1));
            const int x0 = int(u), x1 = std::min(x0 + 1, sw - 1);
            const float fx = u - x0;
            for (int c = 0; c < channels; ++c) {
                auto at = [&](int xx, int yy) { return src.px[size_t(yy) * src.width + xx * channels + c]; };
                const float top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx;
                const float bot = at(x0, y1) * (1 - fx) + at(x1, y1) * fx;
                out.px[size_t(y) * out.width + x * channels + c] = top * (1 - fy) + bot * fy;
            }
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
