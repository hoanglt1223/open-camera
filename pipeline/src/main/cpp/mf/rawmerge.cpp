/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "rawmerge.h"

#include <cmath>
#include <new>

#include "merge.h"
#include "parallel.h"

namespace mf {

namespace {

constexpr int kTile = 16;  // tile size on the 2x2-binned grid (32x32 sensor pixels)
constexpr double kPi = 3.14159265358979323846;

struct Window {
    float w[kTile];
    Window() {
        for (int k = 0; k < kTile; ++k) w[k] = 0.5f - 0.5f * std::cos(2 * kPi * (k + 0.5) / kTile);
    }
};

const uint16_t *rowOf(const uint16_t *raw, int rowStrideBytes, int y) {
    return reinterpret_cast<const uint16_t *>(reinterpret_cast<const uint8_t *>(raw) + size_t(y) * rowStrideBytes);
}

// Channel (0 R, 1 G, 2 B) of each 2x2 CFA position in reading order.
void cfaColors(Cfa cfa, int out[4]) {
    static const int layouts[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    for (int i = 0; i < 4; ++i) out[i] = layouts[int(cfa)][i];
}

bool fail(std::string *error, const char *what) {
    if (error) *error = what;
    return false;
}

}  // namespace

Gray RawMerger::binnedLuma(const uint16_t *raw, int rowStrideBytes, const float black[4]) const {
    const int hw = width_ / 2, hh = height_ / 2;
    Gray g(hw, hh);
    const float blackSum = black[0] + black[1] + black[2] + black[3];
    const float scale = 1.0f / std::max(4.0f, 4 * params_.white - blackSum);
    parallelFor(0, hh, [&](int by) {
        const uint16_t *r0 = rowOf(raw, rowStrideBytes, 2 * by);
        const uint16_t *r1 = rowOf(raw, rowStrideBytes, 2 * by + 1);
        uint8_t *out = &g.px[size_t(by) * hw];
        for (int bx = 0; bx < hw; ++bx) {
            const float s = float(r0[2 * bx]) + r0[2 * bx + 1] + r1[2 * bx] + r1[2 * bx + 1] - blackSum;
            // Square root roughly evens out shot noise, so one noise level fits dark and bright tiles.
            out[bx] = clampU8(255.0f * std::sqrt(std::clamp(s * scale, 0.0f, 1.0f)));
        }
    });
    return g;
}

void RawMerger::accumulate(const uint16_t *raw, int rowStrideBytes, const float black[4],
                           const std::vector<Displacement> *disp, const std::vector<float> *tileWeights) {
    static const Window win;
    const int hw = width_ / 2, hh = height_ / 2;
    auto tileRow = [&](int ty) {
        for (int tx = 0; tx < grid_.cols; ++tx) {
            const size_t t = size_t(ty) * grid_.cols + tx;
            const float tw = tileWeights ? (*tileWeights)[t] : 1.0f;
            if (tw <= 0) continue;
            const int dx = disp ? int(std::lround((*disp)[t].dx)) : 0;
            const int dy = disp ? int(std::lround((*disp)[t].dy)) : 0;
            const int x0 = grid_.origin + tx * grid_.stride, y0 = grid_.origin + ty * grid_.stride;
            for (int j = 0; j < kTile; ++j) {
                const int by = y0 + j;
                if (by < 0 || by >= hh) continue;
                const int sy = std::clamp(by + dy, 0, hh - 1);
                const uint16_t *s0 = rowOf(raw, rowStrideBytes, 2 * sy);
                const uint16_t *s1 = rowOf(raw, rowStrideBytes, 2 * sy + 1);
                float *d0 = &sum_[size_t(2 * by) * width_];
                float *d1 = d0 + width_;
                float *wr = &weight_[size_t(by) * hw];
                for (int i = 0; i < kTile; ++i) {
                    const int bx = x0 + i;
                    if (bx < 0 || bx >= hw) continue;
                    const int sx = std::clamp(bx + dx, 0, hw - 1);
                    const float w = win.w[i] * win.w[j] * tw;
                    d0[2 * bx] += w * (float(s0[2 * sx]) - black[0]);
                    d0[2 * bx + 1] += w * (float(s0[2 * sx + 1]) - black[1]);
                    d1[2 * bx] += w * (float(s1[2 * sx]) - black[2]);
                    d1[2 * bx + 1] += w * (float(s1[2 * sx + 1]) - black[3]);
                    wr[bx] += w;
                }
            }
        }
    };
    // Tiles overlap their direct neighbours only, so every other tile row can run in parallel.
    for (int phase = 0; phase < 2; ++phase) {
        parallelFor(0, (grid_.rows - phase + 1) / 2, [&](int k) { tileRow(2 * k + phase); });
    }
}

bool RawMerger::begin(int width, int height, const RawMergeParams &params, const uint16_t *raw,
                      int rowStrideBytes, const float black[4], std::string *error) {
    try {
        width &= ~1;
        height &= ~1;
        if (!raw || width < 64 || height < 64 || rowStrideBytes < width * 2) return fail(error, "unsupported frame");
        if (int(params.cfa) < 0 || int(params.cfa) > 3) return fail(error, "unsupported CFA");
        if (!(params.white > black[0] + 16)) return fail(error, "bad white level");
        if (!params.lsc.empty() &&
            (params.lscW < 2 || params.lscH < 2 || params.lsc.size() < size_t(params.lscW) * params.lscH * 4)) {
            return fail(error, "bad lens shading map");
        }
        width_ = width;
        height_ = height;
        std::copy_n(black, 4, refBlack_);
        params_ = params;
        frames_ = 0;
        mosaic_.clear();
        grid_ = TileGrid::cover(width / 2, height / 2, kTile);
        sum_.assign(size_t(width) * height, 0.0f);
        weight_.assign(size_t(width / 2) * (height / 2), 0.0f);
        Gray g = binnedLuma(raw, rowStrideBytes, black);
        noise_ = estimateNoise(g);
        ref_ = buildPyramid(std::move(g));
        accumulate(raw, rowStrideBytes, black, nullptr, nullptr);
        frames_ = 1;
        return true;
    } catch (const std::bad_alloc &) {
        sum_ = {};
        weight_ = {};
        return fail(error, "out of memory");
    }
}

bool RawMerger::add(const uint16_t *raw, int rowStrideBytes, const float black[4], float *weight,
                    std::string *error) {
    if (frames_ < 1 || sum_.empty()) return fail(error, "merge not started");
    if (!raw || rowStrideBytes < width_ * 2) return fail(error, "unsupported frame");
    try {
        const Pyramid alt = buildPyramid(binnedLuma(raw, rowStrideBytes, black));
        const std::vector<Displacement> disp = alignTiles(ref_, alt, grid_);
        const Gray &rg = ref_.level[0], &ag = alt.level[0];
        const float t1 = params_.lowSigma * noise_ + 0.5f;
        const float t2 = std::max(params_.highSigma * noise_ + 1.5f, t1 + 0.5f);
        std::vector<float> tileWeights(disp.size());
        parallelFor(0, grid_.rows, [&](int ty) {
            for (int tx = 0; tx < grid_.cols; ++tx) {
                const size_t t = size_t(ty) * grid_.cols + tx;
                const int dx = int(std::lround(disp[t].dx)), dy = int(std::lround(disp[t].dy));
                const int x0 = grid_.origin + tx * grid_.stride, y0 = grid_.origin + ty * grid_.stride;
                float diff = 0;
                int n = 0;
                for (int j = 0; j < kTile; ++j) {
                    const int y = y0 + j;
                    if (y < 0 || y >= rg.height) continue;
                    for (int i = 0; i < kTile; ++i) {
                        const int x = x0 + i;
                        if (x < 0 || x >= rg.width) continue;
                        diff += std::abs(float(ag.at(x + dx, y + dy)) - rg.px[size_t(y) * rg.width + x]);
                        ++n;
                    }
                }
                const float d = n ? diff / n : t2;
                tileWeights[t] = std::clamp((t2 - d) / (t2 - t1), 0.0f, 1.0f);
            }
        });
        double total = 0;
        for (float w : tileWeights) total += w;
        if (weight) *weight = tileWeights.empty() ? 0.0f : float(total / tileWeights.size());
        accumulate(raw, rowStrideBytes, black, &disp, &tileWeights);
        ++frames_;
        return true;
    } catch (const std::bad_alloc &) {
        return fail(error, "out of memory");
    }
}

bool RawMerger::finish(std::string *error) {
    if (frames_ < 1 || sum_.empty()) return fail(error, "merge not started");
    try {
        mosaic_.resize(size_t(width_) * height_);
        int colors[4];
        cfaColors(params_.cfa, colors);
        const bool lsc = !params_.lsc.empty();
        const int hw = width_ / 2;
        // Every frame had its own black level subtracted; the range is the reference frame's.
        float scale[4];
        for (int i = 0; i < 4; ++i) scale[i] = 65535.0f / std::max(16.0f, params_.white - refBlack_[i]);
        parallelFor(0, height_, [&](int y) {
            float gy = 0;
            int ly0 = 0;
            if (lsc) {
                gy = float(y) / (height_ - 1) * (params_.lscH - 1);
                ly0 = std::min(int(gy), params_.lscH - 2);
                gy -= ly0;
            }
            const float *s = &sum_[size_t(y) * width_];
            const float *wr = &weight_[size_t(y / 2) * hw];
            uint16_t *out = &mosaic_[size_t(y) * width_];
            for (int x = 0; x < width_; ++x) {
                const float w = wr[x / 2];
                float v = w > 0 ? s[x] / w : 0.0f;
                if (lsc) {
                    const int color = colors[(y & 1) * 2 + (x & 1)];
                    const int gi = color == 0 ? 0 : color == 2 ? 3 : ((y & 1) ? 2 : 1);
                    float gx = float(x) / (width_ - 1) * (params_.lscW - 1);
                    const int lx0 = std::min(int(gx), params_.lscW - 2);
                    gx -= lx0;
                    auto at = [&](int lx, int ly) {
                        return params_.lsc[(size_t(ly) * params_.lscW + lx) * 4 + gi];
                    };
                    v *= (at(lx0, ly0) * (1 - gx) + at(lx0 + 1, ly0) * gx) * (1 - gy) +
                         (at(lx0, ly0 + 1) * (1 - gx) + at(lx0 + 1, ly0 + 1) * gx) * gy;
                }
                out[x] = uint16_t(std::clamp(v * scale[(y & 1) * 2 + (x & 1)] + 0.5f, 0.0f, 65535.0f));
            }
        });
        sum_ = {};
        weight_ = {};
        ref_ = {};
        return true;
    } catch (const std::bad_alloc &) {
        mosaic_ = {};
        return fail(error, "out of memory");
    }
}

bool RawMerger::demosaicRows(int y0, int rows, uint16_t *out) const {
    if (mosaic_.empty() || !out || y0 < 0 || rows < 1 || y0 + rows > height_) return false;
    int colors[4];
    cfaColors(params_.cfa, colors);
    const int W = width_, H = height_;
    auto px = [&](int x, int y) -> float {
        x = x < 0 ? 1 : x >= W ? W - 2 : x;  // mirror keeps the CFA phase
        y = y < 0 ? 1 : y >= H ? H - 2 : y;
        return mosaic_[size_t(y) * W + x];
    };
    auto colorAt = [&](int x, int y) { return colors[(y & 1) * 2 + (x & 1)]; };
    parallelFor(0, rows, [&](int r) {
        const int y = y0 + r;
        uint16_t *o = out + size_t(r) * W * 3;
        for (int x = 0; x < W; ++x) {
            const int c = colorAt(x, y);
            float rgb[3];
            rgb[c] = px(x, y);
            if (c == 1) {
                const int ch = colorAt(x + 1, y);
                rgb[ch] = 0.5f * (px(x - 1, y) + px(x + 1, y));
                rgb[2 - ch] = 0.5f * (px(x, y - 1) + px(x, y + 1));
            } else {
                rgb[1] = 0.25f * (px(x - 1, y) + px(x + 1, y) + px(x, y - 1) + px(x, y + 1));
                rgb[2 - c] = 0.25f * (px(x - 1, y - 1) + px(x + 1, y - 1) + px(x - 1, y + 1) + px(x + 1, y + 1));
            }
            for (int k = 0; k < 3; ++k) o[3 * x + k] = uint16_t(rgb[k] + 0.5f);
        }
    });
    return true;
}

}  // namespace mf
