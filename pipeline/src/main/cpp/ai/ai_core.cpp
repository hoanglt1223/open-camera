// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0

#include "ai_core.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <new>
#include <vector>

#include "../mf/parallel.h"

namespace aicore {
namespace {

// parallelFor whose workers never let an exception escape a thread (that would terminate the
// process); the first failure is rethrown as bad_alloc on the calling thread.
template <typename Fn>
void safeParallelFor(int begin, int end, Fn&& fn) {
    std::atomic<bool> failed{false};
    mf::parallelFor(begin, end, [&](int i) {
        if (failed.load(std::memory_order_relaxed)) return;
        try {
            fn(i);
        } catch (...) {
            failed = true;
        }
    });
    if (failed) throw std::bad_alloc();
}

inline float r8(uint32_t c) { return float(c & 0xff); }
inline float g8(uint32_t c) { return float((c >> 8) & 0xff); }
inline float b8(uint32_t c) { return float((c >> 16) & 0xff); }
inline uint32_t pack(float r, float g, float b, uint32_t a) {
    auto q = [](float v) { return uint32_t(std::clamp(v, 0.f, 255.f) + 0.5f); };
    return q(r) | (q(g) << 8) | (q(b) << 16) | (a & 0xff000000u);
}
inline const uint32_t* prow(const Image& im, int y) { return reinterpret_cast<const uint32_t*>(im.row(y)); }
inline uint32_t* wrow(const Image& im, int y) { return reinterpret_cast<uint32_t*>(im.row(y)); }

// Bilinear sample of a w x h float map at source coordinates.
inline float bilinear(const float* m, int w, int h, float x, float y) {
    x = std::clamp(x, 0.f, float(w - 1));
    y = std::clamp(y, 0.f, float(h - 1));
    int x0 = int(x), y0 = int(y);
    int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    float fx = x - x0, fy = y - y0;
    float a = m[y0 * w + x0] * (1 - fx) + m[y0 * w + x1] * fx;
    float b = m[y1 * w + x0] * (1 - fx) + m[y1 * w + x1] * fx;
    return a * (1 - fy) + b * fy;
}

}  // namespace

void boxMean(const float* src, float* dst, int w, int h, int r) {
    std::vector<float> tmp(size_t(w) * h);
    const float k = 1.f / float(2 * r + 1);
    for (int y = 0; y < h; ++y) {
        const float* s = src + size_t(y) * w;
        float* t = tmp.data() + size_t(y) * w;
        float acc = 0;
        for (int x = -r; x <= r; ++x) acc += s[std::clamp(x, 0, w - 1)];
        for (int x = 0; x < w; ++x) {
            t[x] = acc * k;
            acc += s[std::min(x + r + 1, w - 1)] - s[std::max(x - r, 0)];
        }
    }
    std::vector<float> acc(w, 0.f);
    for (int y = -r; y <= r; ++y) {
        const float* t = tmp.data() + size_t(std::clamp(y, 0, h - 1)) * w;
        for (int x = 0; x < w; ++x) acc[x] += t[x];
    }
    for (int y = 0; y < h; ++y) {
        float* d = dst + size_t(y) * w;
        const float* add = tmp.data() + size_t(std::min(y + r + 1, h - 1)) * w;
        const float* sub = tmp.data() + size_t(std::max(y - r, 0)) * w;
        for (int x = 0; x < w; ++x) {
            d[x] = acc[x] * k;
            acc[x] += add[x] - sub[x];
        }
    }
}

void guidedFilter(const float* I, const float* p, float* q, int w, int h, int r, float eps) {
    const size_t n = size_t(w) * h;
    std::vector<float> mI(n), mP(n), ip(n), ii(n), a(n), b(n);
    for (size_t i = 0; i < n; ++i) {
        ip[i] = I[i] * p[i];
        ii[i] = I[i] * I[i];
    }
    boxMean(I, mI.data(), w, h, r);
    boxMean(p, mP.data(), w, h, r);
    boxMean(ip.data(), ip.data(), w, h, r);
    boxMean(ii.data(), ii.data(), w, h, r);
    for (size_t i = 0; i < n; ++i) {
        float var = ii[i] - mI[i] * mI[i];
        float cov = ip[i] - mI[i] * mP[i];
        a[i] = cov / (var + eps);
        b[i] = mP[i] - a[i] * mI[i];
    }
    boxMean(a.data(), a.data(), w, h, r);
    boxMean(b.data(), b.data(), w, h, r);
    for (size_t i = 0; i < n; ++i) q[i] = a[i] * I[i] + b[i];
}

void refineMask(const Image& guide, float* mask, int radius, float eps) {
    const int w = guide.w, h = guide.h;
    std::vector<float> lum(size_t(w) * h), out(size_t(w) * h);
    for (int y = 0; y < h; ++y) {
        const uint32_t* s = prow(guide, y);
        for (int x = 0; x < w; ++x) {
            uint32_t c = s[x];
            lum[size_t(y) * w + x] = (0.299f * r8(c) + 0.587f * g8(c) + 0.114f * b8(c)) / 255.f;
        }
    }
    guidedFilter(lum.data(), mask, out.data(), w, h, radius, eps);
    for (size_t i = 0; i < out.size(); ++i) mask[i] = std::clamp(out[i], 0.f, 1.f);
}

void lensBlur(const Image& src, const float* sharpness, float radius, const Image& out) {
    const int w = src.w, h = src.h;
    const int R = std::max(0, int(std::lround(radius)));
    if (R == 0) {
        for (int y = 0; y < h; ++y) std::copy(prow(src, y), prow(src, y) + w, wrow(out, y));
        return;
    }
    // Row prefix sums of weighted linear RGB and of the weights.
    const int pw = w + 1;
    std::vector<float> pre(size_t(pw) * h * 4);
    safeParallelFor(0, h, [&](int y) {
        const uint32_t* s = prow(src, y);
        float* p = pre.data() + size_t(y) * pw * 4;
        p[0] = p[1] = p[2] = p[3] = 0;
        for (int x = 0; x < w; ++x) {
            uint32_t c = s[x];
            float r = r8(c) / 255.f, g = g8(c) / 255.f, b = b8(c) / 255.f;
            r *= r;
            g *= g;
            b *= b;
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float hl = std::max(0.f, lum - 0.55f) / 0.45f;
            float wgt = (1.f - std::clamp(sharpness[size_t(y) * w + x], 0.f, 1.f)) * (1.f + 6.f * hl * hl);
            float* q = p + (x + 1) * 4;
            float* o = p + x * 4;
            q[0] = o[0] + r * wgt;
            q[1] = o[1] + g * wgt;
            q[2] = o[2] + b * wgt;
            q[3] = o[3] + wgt;
        }
    });
    std::vector<int> half(2 * R + 1);
    for (int dy = -R; dy <= R; ++dy) half[dy + R] = int(std::sqrt(float(R * R - dy * dy)));
    safeParallelFor(0, h, [&](int y) {
        const uint32_t* s = prow(src, y);
        uint32_t* d = wrow(out, y);
        for (int x = 0; x < w; ++x) {
            float acc[4] = {0, 0, 0, 0};
            for (int dy = -R; dy <= R; ++dy) {
                int yy = std::clamp(y + dy, 0, h - 1);
                int hw = half[dy + R];
                int x0 = std::max(x - hw, 0), x1 = std::min(x + hw, w - 1);
                const float* p = pre.data() + size_t(yy) * pw * 4;
                const float* a = p + x0 * 4;
                const float* b = p + (x1 + 1) * 4;
                acc[0] += b[0] - a[0];
                acc[1] += b[1] - a[1];
                acc[2] += b[2] - a[2];
                acc[3] += b[3] - a[3];
            }
            if (acc[3] > 1e-3f) {
                float k = 1.f / acc[3];
                d[x] = pack(std::sqrt(acc[0] * k) * 255.f, std::sqrt(acc[1] * k) * 255.f,
                            std::sqrt(acc[2] * k) * 255.f, s[x]);
            } else {
                d[x] = s[x];
            }
        }
    });
}

void composite(const Image& full, const Image& blurred, const float* sharpness) {
    const int bw = blurred.w, bh = blurred.h;
    const float sx = float(bw) / full.w, sy = float(bh) / full.h;
    safeParallelFor(0, full.h, [&](int y) {
        uint32_t* d = wrow(full, y);
        float fy = (y + 0.5f) * sy - 0.5f;
        fy = std::clamp(fy, 0.f, float(bh - 1));
        int y0 = int(fy), y1 = std::min(y0 + 1, bh - 1);
        float ty = fy - y0;
        const uint32_t* r0 = prow(blurred, y0);
        const uint32_t* r1 = prow(blurred, y1);
        for (int x = 0; x < full.w; ++x) {
            float fx = std::clamp((x + 0.5f) * sx - 0.5f, 0.f, float(bw - 1));
            int x0 = int(fx), x1 = std::min(x0 + 1, bw - 1);
            float tx = fx - x0;
            float a = sharpness[y0 * bw + x0] * (1 - tx) * (1 - ty) + sharpness[y0 * bw + x1] * tx * (1 - ty) +
                      sharpness[y1 * bw + x0] * (1 - tx) * ty + sharpness[y1 * bw + x1] * tx * ty;
            a = std::clamp(a, 0.f, 1.f);
            if (a > 0.995f) continue;
            float w00 = (1 - tx) * (1 - ty), w01 = tx * (1 - ty), w10 = (1 - tx) * ty, w11 = tx * ty;
            uint32_t c00 = r0[x0], c01 = r0[x1], c10 = r1[x0], c11 = r1[x1];
            float br = r8(c00) * w00 + r8(c01) * w01 + r8(c10) * w10 + r8(c11) * w11;
            float bg = g8(c00) * w00 + g8(c01) * w01 + g8(c10) * w10 + g8(c11) * w11;
            float bb = b8(c00) * w00 + b8(c01) * w01 + b8(c10) * w10 + b8(c11) * w11;
            uint32_t c = d[x];
            d[x] = pack(r8(c) * a + br * (1 - a), g8(c) * a + bg * (1 - a), b8(c) * a + bb * (1 - a), c);
        }
    });
}

void smoothSkin(const Image& full, const float* mask, int mw, int mh, float strength) {
    if (strength <= 0.f) return;
    const int W = full.w, H = full.h;
    const int f = std::max(1, int(std::ceil(std::max(W, H) / 1280.f)));
    const int w = std::max(2, W / f), h = std::max(2, H / f);
    const size_t n = size_t(w) * h;
    // D: box-downsampled image, S: its edge-preserving smoothing, per channel.
    std::vector<float> D(n * 3), S(n * 3);
    safeParallelFor(0, h, [&](int y) {
        for (int x = 0; x < w; ++x) {
            float acc[3] = {0, 0, 0};
            int cnt = 0;
            for (int yy = y * f; yy < std::min(H, (y + 1) * f); ++yy) {
                const uint32_t* s = prow(full, yy);
                for (int xx = x * f; xx < std::min(W, (x + 1) * f); ++xx) {
                    acc[0] += r8(s[xx]);
                    acc[1] += g8(s[xx]);
                    acc[2] += b8(s[xx]);
                    ++cnt;
                }
            }
            for (int c = 0; c < 3; ++c) D[c * n + size_t(y) * w + x] = acc[c] / (255.f * std::max(cnt, 1));
        }
    });
    const int r = std::max(3, std::max(w, h) / 160);
    const float eps = 0.0016f + 0.004f * strength;
    safeParallelFor(0, 3, [&](int c) { guidedFilter(&D[c * n], &D[c * n], &S[c * n], w, h, r, eps); });
    for (size_t i = 0; i < n * 3; ++i) S[i] -= D[i];  // mid-frequency detail to remove
    const float sx = float(w) / W, sy = float(h) / H, mx = float(mw) / W, my = float(mh) / H;
    safeParallelFor(0, H, [&](int y) {
        uint32_t* d = wrow(full, y);
        for (int x = 0; x < W; ++x) {
            float m = bilinear(mask, mw, mh, (x + 0.5f) * mx - 0.5f, (y + 0.5f) * my - 0.5f) * strength;
            if (m < 0.01f) continue;
            float u = (x + 0.5f) * sx - 0.5f, v = (y + 0.5f) * sy - 0.5f;
            float lift = 1.f + 0.05f * m;
            uint32_t c = d[x];
            float dr = bilinear(&S[0], w, h, u, v) * 255.f;
            float dg = bilinear(&S[n], w, h, u, v) * 255.f;
            float db = bilinear(&S[2 * n], w, h, u, v) * 255.f;
            d[x] = pack((r8(c) + m * dr) * lift, (g8(c) + m * dg) * lift, (b8(c) + m * db) * lift, c);
        }
    });
}

namespace {

constexpr int kStrip = 64;

// Runs fn(y0, y1, ys0, ys1) for core rows [y0, y1) with source rows [ys0, ys1) including a
// [pad]-row apron so strip borders do not show.
template <typename Fn>
void strips(int h, int pad, Fn&& fn) {
    int count = (h + kStrip - 1) / kStrip;
    safeParallelFor(0, count, [&](int i) {
        int y0 = i * kStrip, y1 = std::min(h, y0 + kStrip);
        fn(y0, y1, std::max(0, y0 - pad), std::min(h, y1 + pad));
    });
}

}  // namespace

void denoise(const Image& full, float strength) {
    if (strength <= 0.f) return;
    const int W = full.w;
    const int rl = 2, rc = 6, pad = 2 * rc + 2;
    const float epsL = std::pow(0.01f + 0.04f * strength, 2.f);
    const float epsC = 0.0004f;
    strips(full.h, pad, [&](int y0, int y1, int ys0, int ys1) {
        const int h = ys1 - ys0;
        const size_t n = size_t(W) * h;
        std::vector<float> Y(n), Cb(n), Cr(n), Yq(n), Cbq(n), Crq(n);
        for (int y = 0; y < h; ++y) {
            const uint32_t* s = prow(full, ys0 + y);
            for (int x = 0; x < W; ++x) {
                float r = r8(s[x]) / 255.f, g = g8(s[x]) / 255.f, b = b8(s[x]) / 255.f;
                size_t i = size_t(y) * W + x;
                Y[i] = 0.299f * r + 0.587f * g + 0.114f * b;
                Cb[i] = b - Y[i];
                Cr[i] = r - Y[i];
            }
        }
        guidedFilter(Y.data(), Y.data(), Yq.data(), W, h, rl, epsL);
        guidedFilter(Yq.data(), Cb.data(), Cbq.data(), W, h, rc, epsC);
        guidedFilter(Yq.data(), Cr.data(), Crq.data(), W, h, rc, epsC);
        for (int y = y0; y < y1; ++y) {
            uint32_t* d = wrow(full, y);
            for (int x = 0; x < W; ++x) {
                size_t i = size_t(y - ys0) * W + x;
                float yy = Y[i] + strength * (Yq[i] - Y[i]);
                float cb = Cb[i] + strength * (Cbq[i] - Cb[i]);
                float cr = Cr[i] + strength * (Crq[i] - Cr[i]);
                float r = yy + cr, b = yy + cb;
                float g = (yy - 0.299f * r - 0.114f * b) / 0.587f;
                d[x] = pack(r * 255.f, g * 255.f, b * 255.f, d[x]);
            }
        }
    });
}

void sharpen(const Image& full, float amount) {
    if (amount <= 0.f) return;
    const int W = full.w, r = 1, pad = 2 * r + 1;
    strips(full.h, pad, [&](int y0, int y1, int ys0, int ys1) {
        const int h = ys1 - ys0;
        const size_t n = size_t(W) * h;
        std::vector<float> Y(n), B(n);
        for (int y = 0; y < h; ++y) {
            const uint32_t* s = prow(full, ys0 + y);
            for (int x = 0; x < W; ++x) Y[size_t(y) * W + x] = 0.299f * r8(s[x]) + 0.587f * g8(s[x]) + 0.114f * b8(s[x]);
        }
        boxMean(Y.data(), B.data(), W, h, r);
        boxMean(B.data(), B.data(), W, h, r);
        for (int y = y0; y < y1; ++y) {
            uint32_t* d = wrow(full, y);
            for (int x = 0; x < W; ++x) {
                size_t i = size_t(y - ys0) * W + x;
                float delta = amount * (Y[i] - B[i]);
                uint32_t c = d[x];
                d[x] = pack(r8(c) + delta, g8(c) + delta, b8(c) + delta, c);
            }
        }
    });
}

}  // namespace aicore
