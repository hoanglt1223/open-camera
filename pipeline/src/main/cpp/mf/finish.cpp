/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "finish.h"

#include <cmath>
#include <vector>

#include "parallel.h"

namespace mf {

namespace {

constexpr int kGainScale = 8;
constexpr float kGamma = 2.2f;

GrayF down2(const GrayF &a) {
    GrayF d((a.width + 1) / 2, (a.height + 1) / 2);
    for (int y = 0; y < d.height; ++y) {
        for (int x = 0; x < d.width; ++x) {
            d.px[size_t(y) * d.width + x] = 0.25f * (a.at(2 * x, 2 * y) + a.at(2 * x + 1, 2 * y) +
                                                     a.at(2 * x, 2 * y + 1) + a.at(2 * x + 1, 2 * y + 1));
        }
    }
    return d;
}

float sampleBilinear(const GrayF &a, float u, float v) {
    const int x0 = int(std::floor(u)), y0 = int(std::floor(v));
    const float fx = u - x0, fy = v - y0;
    const float top = a.at(x0, y0) * (1 - fx) + a.at(x0 + 1, y0) * fx;
    const float bot = a.at(x0, y0 + 1) * (1 - fx) + a.at(x0 + 1, y0 + 1) * fx;
    return top * (1 - fy) + bot * fy;
}

GrayF upTo(const GrayF &a, int w, int h) {
    GrayF u(w, h);
    const float sx = float(a.width) / w, sy = float(a.height) / h;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            u.px[size_t(y) * w + x] = sampleBilinear(a, (x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f);
        }
    }
    return u;
}

std::vector<GrayF> gaussianPyramid(const GrayF &a, int levels) {
    std::vector<GrayF> p{a};
    for (int l = 1; l < levels; ++l) p.push_back(down2(p.back()));
    return p;
}

std::vector<GrayF> laplacianPyramid(const GrayF &a, int levels) {
    std::vector<GrayF> g = gaussianPyramid(a, levels);
    for (int l = 0; l + 1 < levels; ++l) {
        GrayF up = upTo(g[l + 1], g[l].width, g[l].height);
        for (size_t i = 0; i < up.px.size(); ++i) g[l].px[i] -= up.px[i];
    }
    return g;
}

GrayF boxDown(const GrayF &y, int f) {
    GrayF d(y.width / f, y.height / f);
    const float inv = 1.0f / (f * f * 255.0f);
    parallelFor(0, d.height, [&](int r) {
        for (int x = 0; x < d.width; ++x) {
            float s = 0;
            for (int j = 0; j < f; ++j) {
                const float *row = &y.px[size_t(r * f + j) * y.width + x * f];
                for (int i = 0; i < f; ++i) s += row[i];
            }
            d.px[size_t(r) * d.width + x] = s * inv;
        }
    });
    return d;
}

}  // namespace

void localToneMap(GrayF &y, GrayF &vu, const ToneParams &params) {
    if (params.evStep <= 0 || params.exposures < 2) return;
    const GrayF base = boxDown(y, kGainScale);  // [0, 1], gamma encoded
    if (base.width < 4 || base.height < 4) return;
    const size_t n = base.px.size();

    int levels = 1;
    for (int m = std::min(base.width, base.height); m > 8 && levels < 7; m /= 2) ++levels;

    std::vector<GrayF> exposures, weights;
    for (int k = 0; k < params.exposures; ++k) {
        const float gain = std::exp2(k * params.evStep);
        GrayF e(base.width, base.height);
        for (size_t i = 0; i < n; ++i) {
            const float lin = std::pow(base.px[i], kGamma) * gain;
            e.px[i] = std::pow(std::min(lin, 1.0f), 1.0f / kGamma);
        }
        GrayF w(base.width, base.height);
        for (int r = 0; r < e.height; ++r) {
            for (int c = 0; c < e.width; ++c) {
                const float v = e.at(c, r);
                const float lap = std::fabs(4 * v - e.at(c - 1, r) - e.at(c + 1, r) - e.at(c, r - 1) - e.at(c, r + 1));
                const float wellExposed = std::exp(-(v - 0.5f) * (v - 0.5f) / (2 * 0.2f * 0.2f));
                w.px[size_t(r) * w.width + c] = wellExposed * (lap + 0.05f) + 1e-6f;
            }
        }
        exposures.push_back(std::move(e));
        weights.push_back(std::move(w));
    }
    for (size_t i = 0; i < n; ++i) {
        float s = 0;
        for (auto &w : weights) s += w.px[i];
        for (auto &w : weights) w.px[i] /= s;
    }

    std::vector<GrayF> fused;
    for (int k = 0; k < params.exposures; ++k) {
        std::vector<GrayF> lap = laplacianPyramid(exposures[k], levels);
        std::vector<GrayF> gw = gaussianPyramid(weights[k], levels);
        if (fused.empty()) {
            fused = lap;
            for (auto &l : fused) std::fill(l.px.begin(), l.px.end(), 0.0f);
        }
        for (int l = 0; l < levels; ++l) {
            for (size_t i = 0; i < lap[l].px.size(); ++i) fused[l].px[i] += gw[l].px[i] * lap[l].px[i];
        }
    }
    for (int l = levels - 1; l > 0; --l) {
        GrayF up = upTo(fused[l], fused[l - 1].width, fused[l - 1].height);
        for (size_t i = 0; i < up.px.size(); ++i) fused[l - 1].px[i] += up.px[i];
    }

    GrayF gain(base.width, base.height);
    for (size_t i = 0; i < n; ++i) {
        gain.px[i] = std::clamp(fused[0].px[i] / std::max(base.px[i], 1.0f / 255.0f), 0.8f, params.maxGain);
    }

    // Joint bilateral upsampling: neighbours whose base brightness differs from the full
    // resolution pixel contribute less, which keeps gain from bleeding across strong edges.
    float rangeLut[256];
    for (int i = 0; i < 256; ++i) {
        const float d = i / 255.0f;
        rangeLut[i] = std::exp(-d * d / (2 * 0.08f * 0.08f)) + 1e-4f;
    }
    const int W = y.width, H = y.height;
    GrayF gainHalf(W / 2, H / 2);
    parallelFor(0, H, [&](int r) {
        const float v = (r + 0.5f) / kGainScale - 0.5f;
        const int y0 = int(std::floor(v));
        const float fy = v - y0;
        float *row = &y.px[size_t(r) * W];
        for (int c = 0; c < W; ++c) {
            const float u = (c + 0.5f) / kGainScale - 0.5f;
            const int x0 = int(std::floor(u));
            const float fx = u - x0;
            const float p = row[c];
            float sw = 0, sg = 0;
            for (int j = 0; j < 2; ++j) {
                for (int i = 0; i < 2; ++i) {
                    const float bw = (i ? fx : 1 - fx) * (j ? fy : 1 - fy);
                    const float b = base.at(x0 + i, y0 + j) * 255.0f;
                    const int d = std::min(255, int(std::fabs(p - b)));
                    const float w = bw * rangeLut[d];
                    sw += w;
                    sg += w * gain.at(x0 + i, y0 + j);
                }
            }
            const float g = sg / sw;
            row[c] = std::min(p * g, 255.0f);
            if (!(r & 1) && !(c & 1) && c / 2 < gainHalf.width && r / 2 < gainHalf.height) {
                gainHalf.px[size_t(r / 2) * gainHalf.width + c / 2] = g;
            }
        }
    });
    parallelFor(0, vu.height, [&](int r) {
        float *row = &vu.px[size_t(r) * vu.width];
        for (int c = 0; c + 1 < vu.width; c += 2) {
            const float s = std::sqrt(gainHalf.at(c / 2, r));
            row[c] = 128.0f + (row[c] - 128.0f) * s;
            row[c + 1] = 128.0f + (row[c + 1] - 128.0f) * s;
        }
    });
}

void chromaDenoise(GrayF &vu, float strength) {
    if (strength <= 0) return;
    const int pw = vu.width / 2, H = vu.height;
    const GrayF src = vu;
    const float threshold = 6.0f * strength;
    const float inv = 1.0f / threshold;
    parallelFor(0, H, [&](int r) {
        float *dst = &vu.px[size_t(r) * vu.width];
        for (int c = 0; c < pw; ++c) {
            const float v0 = src.px[size_t(r) * vu.width + 2 * c];
            const float u0 = src.px[size_t(r) * vu.width + 2 * c + 1];
            float sv = 0, su = 0, sw = 0;
            for (int j = -2; j <= 2; ++j) {
                const int yy = std::clamp(r + j, 0, H - 1);
                const float *row = &src.px[size_t(yy) * vu.width];
                for (int i = -2; i <= 2; ++i) {
                    const int xx = std::clamp(c + i, 0, pw - 1);
                    const float v = row[2 * xx], u = row[2 * xx + 1];
                    const float d = (std::fabs(v - v0) + std::fabs(u - u0)) * inv;
                    const float w = std::max(0.0f, 1.0f - d);
                    sv += w * v;
                    su += w * u;
                    sw += w;
                }
            }
            dst[2 * c] = sv / sw;
            dst[2 * c + 1] = su / sw;
        }
    });
}

void suppressHotPixels(GrayF &y, float threshold) {
    const GrayF src = y;
    parallelFor(1, y.height - 1, [&](int r) {
        for (int c = 1; c + 1 < y.width; ++c) {
            const float p = src.px[size_t(r) * y.width + c];
            float mx = 0, sum = 0;
            for (int j = -1; j <= 1; ++j) {
                for (int i = -1; i <= 1; ++i) {
                    if (!i && !j) continue;
                    const float q = src.px[size_t(r + j) * y.width + c + i];
                    mx = std::max(mx, q);
                    sum += q;
                }
            }
            if (p > mx + threshold && p > 1.5f * (sum / 8)) y.px[size_t(r) * y.width + c] = mx;
        }
    });
}

void toFrame(const GrayF &y, const GrayF &vu, Frame &out) {
    out = Frame(y.width, y.height);
    parallelFor(0, y.height, [&](int r) {
        const float *s = &y.px[size_t(r) * y.width];
        uint8_t *d = &out.y[size_t(r) * y.width];
        for (int c = 0; c < y.width; ++c) d[c] = clampU8(s[c]);
    });
    parallelFor(0, vu.height, [&](int r) {
        const float *s = &vu.px[size_t(r) * vu.width];
        uint8_t *d = &out.vu[size_t(r) * vu.width];
        for (int c = 0; c < vu.width; ++c) d[c] = clampU8(s[c]);
    });
}

}  // namespace mf
