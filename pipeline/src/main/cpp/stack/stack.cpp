/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "stack.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <new>
#include <numeric>

#include "align.h"
#include "finish.h"
#include "parallel.h"

namespace mf {

namespace {

// Per-frame displacement field on the half-resolution tile grid, sampled bilinearly between
// tile centres. Values are half-resolution pixels.
struct Field {
    const std::vector<Displacement> *d = nullptr;
    TileGrid grid;

    Displacement at(float hx, float hy) const {
        if (!d || d->empty()) return {};
        const float half = grid.size * 0.5f;
        float gx = (hx - grid.origin - half) / grid.stride;
        float gy = (hy - grid.origin - half) / grid.stride;
        gx = std::clamp(gx, 0.0f, float(grid.cols - 1));
        gy = std::clamp(gy, 0.0f, float(grid.rows - 1));
        const int x0 = std::min(int(gx), grid.cols - 2 < 0 ? 0 : grid.cols - 2);
        const int y0 = std::min(int(gy), grid.rows - 2 < 0 ? 0 : grid.rows - 2);
        const int x1 = std::min(x0 + 1, grid.cols - 1);
        const int y1 = std::min(y0 + 1, grid.rows - 1);
        const float fx = gx - x0, fy = gy - y0;
        const auto &v = *d;
        auto get = [&](int x, int y) { return v[size_t(y) * grid.cols + x]; };
        const Displacement a = get(x0, y0), b = get(x1, y0), c = get(x0, y1), e = get(x1, y1);
        Displacement r;
        r.dx = (a.dx * (1 - fx) + b.dx * fx) * (1 - fy) + (c.dx * (1 - fx) + e.dx * fx) * fy;
        r.dy = (a.dy * (1 - fx) + b.dy * fx) * (1 - fy) + (c.dy * (1 - fx) + e.dy * fx) * fy;
        return r;
    }
};

inline float sampleY(const Frame &f, float x, float y) {
    x = std::clamp(x, 0.0f, float(f.width - 1));
    y = std::clamp(y, 0.0f, float(f.height - 1));
    const int x0 = std::min(int(x), f.width - 2), y0 = std::min(int(y), f.height - 2);
    const float fx = x - x0, fy = y - y0;
    const uint8_t *p = &f.y[size_t(y0) * f.width + x0];
    const float top = p[0] + (p[1] - p[0]) * fx;
    const float bot = p[f.width] + (p[f.width + 1] - p[f.width]) * fx;
    return top + (bot - top) * fy;
}

// Chroma plane sample (c = 0 for V, 1 for U) at half-resolution coordinates.
inline float sampleC(const Frame &f, float x, float y, int c) {
    const int cw = f.width / 2, ch = f.height / 2;
    x = std::clamp(x, 0.0f, float(cw - 1));
    y = std::clamp(y, 0.0f, float(ch - 1));
    const int x0 = std::min(int(x), cw - 2), y0 = std::min(int(y), ch - 2);
    const float fx = x - x0, fy = y - y0;
    auto at = [&](int xx, int yy) { return float(f.vu[size_t(yy) * f.width + 2 * xx + c]); };
    const float top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * fx;
    const float bot = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * fx;
    return top + (bot - top) * fy;
}

// |Laplacian| of the half-resolution luma, box-blurred: local focus measure.
GrayF focusMap(const Gray &g) {
    GrayF lap(g.width, g.height), out(g.width, g.height);
    parallelFor(0, g.height, [&](int y) {
        for (int x = 0; x < g.width; ++x) {
            const float v = 4.0f * g.at(x, y) - g.at(x - 1, y) - g.at(x + 1, y) - g.at(x, y - 1) -
                            g.at(x, y + 1);
            lap.px[size_t(y) * g.width + x] = std::fabs(v);
        }
    });
    gaussianBlur(lap, out, 3.0f);
    return out;
}

bool run(const std::vector<Frame> &frames, const StackParams &params, StackResult &result,
         std::string *error) {
    if (frames.empty()) {
        if (error) *error = "no frames";
        return false;
    }
    const int W = frames[0].width, H = frames[0].height;
    if (W < 64 || H < 64 || (W & 1) || (H & 1)) {
        if (error) *error = "unsupported frame size";
        return false;
    }
    for (const Frame &f : frames) {
        if (f.width != W || f.height != H || f.y.size() != size_t(W) * H ||
            f.vu.size() != size_t(W) * (H / 2)) {
            if (error) *error = "inconsistent frame sizes";
            return false;
        }
    }
    const int n = int(frames.size());

    std::vector<Pyramid> pyr(n);
    std::vector<double> sharp(n);
    for (int i = 0; i < n; ++i) {
        pyr[i] = buildPyramid(halfResLuma(frames[i]));
        sharp[i] = sharpness(pyr[i].level[1]);
    }

    // Frames that take part, and the reference everything is aligned to.
    std::vector<int> used(n);
    std::iota(used.begin(), used.end(), 0);
    int ref = n / 2;
    if (params.op == StackOp::Moon) {
        std::sort(used.begin(), used.end(), [&](int a, int b) { return sharp[a] > sharp[b]; });
        const int keep = std::clamp(int(std::ceil(n * params.keepFraction)), 1, n);
        used.resize(keep);
        ref = used[0];
    } else if (params.op != StackOp::Focus) {
        ref = int(std::max_element(sharp.begin(), sharp.begin() + std::min(n, 3)) - sharp.begin());
    }
    if (params.reference >= 0 && params.reference < n) ref = params.reference;
    if (std::find(used.begin(), used.end(), ref) == used.end()) used.push_back(ref);

    const TileGrid grid = TileGrid::cover(W / 2, H / 2, 16);
    std::vector<std::vector<Displacement>> disp(n);
    std::vector<Field> field(n);
    for (int i : used) {
        if (params.align && i != ref) disp[i] = alignTiles(pyr[ref], pyr[i], grid);
        field[i] = Field{&disp[i], grid};
    }

    std::vector<GrayF> focus;
    if (params.op == StackOp::Focus) {
        focus.resize(n);
        for (int i : used) focus[i] = focusMap(pyr[i].level[0]);
    }

    const int m = int(used.size());
    GrayF outY(W, H), outVU(W, H / 2);

    // Luma.
    parallelFor(0, H, [&](int y) {
        std::vector<float> vals(m), w(m);
        for (int x = 0; x < W; ++x) {
            for (int k = 0; k < m; ++k) {
                const int i = used[k];
                const Displacement d = field[i].at(x * 0.5f, y * 0.5f);
                vals[k] = sampleY(frames[i], x + 2 * d.dx, y + 2 * d.dy);
                if (params.op == StackOp::Focus) {
                    const float s = focus[i].at(int((x + 2 * d.dx) * 0.5f), int((y + 2 * d.dy) * 0.5f));
                    w[k] = s * s * s * s + 1e-3f;
                }
            }
            float v = 0;
            switch (params.op) {
                case StackOp::Max:
                    v = *std::max_element(vals.begin(), vals.end());
                    break;
                case StackOp::Median: {
                    std::nth_element(vals.begin(), vals.begin() + m / 2, vals.end());
                    v = vals[m / 2];
                    break;
                }
                case StackOp::Focus: {
                    float sw = 0;
                    for (int k = 0; k < m; ++k) {
                        v += vals[k] * w[k];
                        sw += w[k];
                    }
                    v /= sw;
                    break;
                }
                default:
                    for (float a : vals) v += a;
                    v /= m;
            }
            outY.px[size_t(y) * W + x] = v;
        }
    });

    // Chroma, following the frame that the luma rule picked where the rule is a selection.
    parallelFor(0, H / 2, [&](int cy) {
        std::vector<float> lum(m), w(m), cv(m), cu(m);
        std::vector<int> order(m);
        for (int cx = 0; cx < W / 2; ++cx) {
            for (int k = 0; k < m; ++k) {
                const int i = used[k];
                const Displacement d = field[i].at(cx, cy);
                cv[k] = sampleC(frames[i], cx + d.dx, cy + d.dy, 0);
                cu[k] = sampleC(frames[i], cx + d.dx, cy + d.dy, 1);
                if (params.op == StackOp::Max || params.op == StackOp::Median) {
                    lum[k] = sampleY(frames[i], 2 * (cx + d.dx), 2 * (cy + d.dy));
                } else if (params.op == StackOp::Focus) {
                    const float s = focus[i].at(int(cx + d.dx), int(cy + d.dy));
                    w[k] = s * s * s * s + 1e-3f;
                }
            }
            float v = 0, u = 0;
            if (params.op == StackOp::Max || params.op == StackOp::Median) {
                std::iota(order.begin(), order.end(), 0);
                std::sort(order.begin(), order.end(), [&](int a, int b) { return lum[a] < lum[b]; });
                const int pick = params.op == StackOp::Max ? order[m - 1] : order[m / 2];
                v = cv[pick];
                u = cu[pick];
            } else if (params.op == StackOp::Focus) {
                float sw = 0;
                for (int k = 0; k < m; ++k) {
                    v += cv[k] * w[k];
                    u += cu[k] * w[k];
                    sw += w[k];
                }
                v /= sw;
                u /= sw;
            } else {
                for (int k = 0; k < m; ++k) {
                    v += cv[k];
                    u += cu[k];
                }
                v /= m;
                u /= m;
            }
            outVU.px[size_t(cy) * W + 2 * cx] = v;
            outVU.px[size_t(cy) * W + 2 * cx + 1] = u;
        }
    });

    if (params.op == StackOp::Moon) {
        GrayF restored = outY;
        richardsonLucy(restored, params.rlSigma, params.rlIterations);
        GrayF blur(W, H);
        gaussianBlur(restored, blur, 2.0f);
        const float k = std::clamp(params.strength, 0.0f, 1.0f);
        for (size_t p = 0; p < restored.px.size(); ++p) {
            const float r = restored.px[p] + params.sharpen * (restored.px[p] - blur.px[p]);
            outY.px[p] += (r - outY.px[p]) * k;
        }
    }

    toFrame(outY, outVU, result.image);
    result.referenceIndex = ref;
    result.framesUsed = m;
    return true;
}

}  // namespace

void gaussianBlur(const GrayF &src, GrayF &dst, float sigma) {
    const int W = src.width, H = src.height;
    const int r = std::max(1, int(std::ceil(sigma * 3)));
    std::vector<float> k(2 * r + 1);
    float sum = 0;
    for (int i = -r; i <= r; ++i) sum += k[i + r] = std::exp(-0.5f * i * i / (sigma * sigma));
    for (float &v : k) v /= sum;
    GrayF tmp(W, H);
    parallelFor(0, H, [&](int y) {
        for (int x = 0; x < W; ++x) {
            float a = 0;
            for (int i = -r; i <= r; ++i) a += k[i + r] * src.at(x + i, y);
            tmp.px[size_t(y) * W + x] = a;
        }
    });
    dst = GrayF(W, H);
    parallelFor(0, H, [&](int y) {
        for (int x = 0; x < W; ++x) {
            float a = 0;
            for (int i = -r; i <= r; ++i) a += k[i + r] * tmp.at(x, y + i);
            dst.px[size_t(y) * W + x] = a;
        }
    });
}

void richardsonLucy(GrayF &img, float sigma, int iterations) {
    iterations = std::clamp(iterations, 0, 15);
    if (iterations == 0 || sigma <= 0) return;
    const GrayF observed = img;
    GrayF est = img, conv(img.width, img.height), corr(img.width, img.height);
    constexpr float kEps = 1.0f;
    for (int it = 0; it < iterations; ++it) {
        gaussianBlur(est, conv, sigma);
        for (size_t p = 0; p < conv.px.size(); ++p) {
            corr.px[p] = (observed.px[p] + kEps) / (conv.px[p] + kEps);
        }
        GrayF back(img.width, img.height);
        gaussianBlur(corr, back, sigma);
        for (size_t p = 0; p < est.px.size(); ++p) {
            // Limited step per iteration keeps ringing around the limb in check.
            est.px[p] = std::clamp(est.px[p] * std::clamp(back.px[p], 0.7f, 1.4f), 0.0f, 255.0f);
        }
    }
    img = std::move(est);
}

std::vector<int> rankBySharpness(const std::vector<Frame> &frames) {
    std::vector<double> s(frames.size());
    for (size_t i = 0; i < frames.size(); ++i) s[i] = sharpness(downsampleBox(halfResLuma(frames[i]), 2));
    std::vector<int> order(frames.size());
    std::iota(order.begin(), order.end(), 0);
    std::sort(order.begin(), order.end(), [&](int a, int b) { return s[a] > s[b]; });
    return order;
}

bool stackBurst(const std::vector<Frame> &frames, const StackParams &params, StackResult &result,
                std::string *error) {
    const auto t0 = std::chrono::steady_clock::now();
    bool ok = false;
    try {
        ok = run(frames, params, result, error);
    } catch (const std::bad_alloc &) {
        if (error) *error = "out of memory";
    } catch (const std::exception &e) {
        if (error) *error = e.what();
    }
    result.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return ok;
}

}  // namespace mf
