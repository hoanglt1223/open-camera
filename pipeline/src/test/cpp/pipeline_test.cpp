/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

// Synthetic-burst tests and timing for the native multi-frame pipeline. Configure
// pipeline/src/main/cpp with -DOPENCAMERA_PIPELINE_TESTS=ON, either for the host or with the NDK
// toolchain for arm64 (then push and run with adb). Pass --perf for 12 MP timings.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>

#include "align.h"
#include "finish.h"
#include "merge.h"
#include "pipeline.h"

using namespace mf;

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++failures;
}

// Continuous, aperiodic textured scene (multi-octave value noise plus hard-edged cells),
// sampled at arbitrary positions.
struct Scene {
    static float hash(int x, int y) {
        uint32_t h = uint32_t(x) * 374761393u + uint32_t(y) * 668265263u;
        h = (h ^ (h >> 13)) * 1274126177u;
        return float((h ^ (h >> 16)) & 0xffff) / 65535.0f;
    }
    static float smooth(float t) { return t * t * (3 - 2 * t); }
    static float noise(float x, float y) {
        const int ix = int(std::floor(x)), iy = int(std::floor(y));
        const float fx = smooth(x - ix), fy = smooth(y - iy);
        const float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }
    float operator()(float x, float y) const {
        float v = 60 + 90 * noise(x / 40, y / 40) + 50 * noise(x / 9 + 100, y / 9) +
                  25 * noise(x / 3 + 200, y / 3);
        // Hard-edged cells give the aligner corners to lock on.
        if (hash(int(std::floor(x / 23)) + 500, int(std::floor(y / 17))) > 0.6f) v += 30;
        return v;
    }
};

Frame render(int w, int h, float dx, float dy, float noise, std::mt19937 &rng, float gain = 1.0f) {
    Scene s;
    std::normal_distribution<float> n(0, noise);
    Frame f(w, h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) f.y[size_t(y) * w + x] = clampU8(gain * s(x + dx, y + dy) + n(rng));
    }
    for (int y = 0; y < h / 2; ++y) {
        for (int x = 0; x < w / 2; ++x) {
            const float cx = 2 * x + dx, cy = 2 * y + dy;
            f.vu[size_t(y) * w + 2 * x] = clampU8(128 + 20 * std::sin(cx * 0.02f) + n(rng) * 0.7f);
            f.vu[size_t(y) * w + 2 * x + 1] = clampU8(128 + 20 * std::cos(cy * 0.03f) + n(rng) * 0.7f);
        }
    }
    return f;
}

double psnrY(const Frame &a, const Frame &b, int margin) {
    double se = 0;
    size_t n = 0;
    for (int y = margin; y < a.height - margin; ++y) {
        for (int x = margin; x < a.width - margin; ++x) {
            const double d = double(a.y[size_t(y) * a.width + x]) - b.y[size_t(y) * a.width + x];
            se += d * d;
            ++n;
        }
    }
    return 10 * std::log10(255.0 * 255.0 / std::max(se / n, 1e-9));
}

void testAlignment() {
    std::mt19937 rng(1);
    const int w = 640, h = 480;
    const float sx = 7.3f, sy = -4.6f;  // full-res shift of the alternate frame
    Frame a = render(w, h, 0, 0, 4, rng);
    Frame b = render(w, h, sx, sy, 4, rng);
    Pyramid pa = buildPyramid(halfResLuma(a)), pb = buildPyramid(halfResLuma(b));
    TileGrid g = TileGrid::cover(w / 2, h / 2);
    auto d = alignTiles(pa, pb, g);
    // alt(x + d) ~ ref(x) and alt(x) = scene(x + s)  =>  d = -s (half-res units: -s/2).
    std::vector<float> err;
    for (int r = 2; r < g.rows - 2; ++r) {
        for (int c = 2; c < g.cols - 2; ++c) {
            const auto &v = d[size_t(r) * g.cols + c];
            err.push_back(std::hypot(v.dx + sx / 2, v.dy + sy / 2));
        }
    }
    std::sort(err.begin(), err.end());
    const float median = err[err.size() / 2], p90 = err[err.size() * 9 / 10];
    std::printf("  alignment error (half-res px): median %.3f p90 %.3f\n", median, p90);
    check(median < 0.2f && p90 < 0.5f, "tile alignment recovers a sub-pixel global shift");

    // Large motion handled by the coarse levels.
    Frame c = render(w, h, -61, 38, 4, rng);
    Pyramid pc = buildPyramid(halfResLuma(c));
    auto d2 = alignTiles(pa, pc, g);
    int good = 0, total = 0;
    for (int r = 6; r < g.rows - 6; ++r) {
        for (int cc = 8; cc < g.cols - 8; ++cc) {
            const auto &v = d2[size_t(r) * g.cols + cc];
            good += std::hypot(v.dx - 30.5f, v.dy + 19.0f) < 0.75f;
            ++total;
        }
    }
    std::printf("  large shift: %d/%d interior tiles within 0.75 px\n", good, total);
    check(good > total * 0.9, "pyramid search follows a 72 px shift");
}

void testMergeSnrAndGhosts() {
    std::mt19937 rng(2);
    const int w = 800, h = 600, n = 8;
    const float noise = 8;
    std::uniform_real_distribution<float> shift(-12, 12);
    std::vector<Frame> frames;
    frames.push_back(render(w, h, 0, 0, noise, rng));
    for (int i = 1; i < n; ++i) frames.push_back(render(w, h, shift(rng), shift(rng), noise, rng));
    Frame clean = render(w, h, 0, 0, 0, rng);

    // A bright square that is somewhere else in every alternate frame (moving object).
    for (int i = 1; i < n; ++i) {
        const int ox = 100 + 60 * i, oy = 150;
        for (int y = oy; y < oy + 50; ++y) {
            for (int x = ox; x < ox + 50; ++x) frames[i].y[size_t(y) * w + x] = 250;
        }
    }

    Params p;
    p.mode = Mode::Hdr;
    p.toneEvStep = 0;  // compare merge only
    p.reference = 0;
    Result r;
    std::string err;
    const bool ok = processBurst(frames, p, r, nullptr, &err);
    check(ok, "processBurst succeeds on an 8-frame burst");
    if (!ok) return;
    check(r.referenceIndex == 0 || r.referenceIndex < 3, "reference chosen from the first frames");

    // Reference may not be frame 0; compare against the clean render at the ref position only if 0.
    if (r.referenceIndex == 0) {
        const double single = psnrY(frames[0], clean, 40);
        const double merged = psnrY(r.image, clean, 40);
        std::printf("  PSNR single %.2f dB, merged %.2f dB (gain %.2f dB, noise sigma est %.2f, mean weight %.2f)\n",
                    single, merged, merged - single, r.noiseSigma, r.meanWeight);
        check(merged - single > 4.0, "8-frame merge improves PSNR by more than 4 dB");

        double ghost = 0;
        int cnt = 0;
        for (int y = 160; y < 190; ++y) {
            for (int x = 170; x < 560; ++x) {
                ghost += std::fabs(double(r.image.y[size_t(y) * w + x]) - clean.y[size_t(y) * w + x]);
                ++cnt;
            }
        }
        ghost /= cnt;
        std::printf("  mean abs error on moving-object path: %.2f\n", ghost);
        check(ghost < 6.0, "moving object rejected (no ghost)");
    } else {
        std::printf("  reference index %d; skipping PSNR comparison\n", r.referenceIndex);
    }
}

void testSuperRes() {
    std::mt19937 rng(3);
    const int w = 512, h = 384, n = 8;
    Scene s;
    // Ground truth at full resolution; frames are a box-downsampled (native) capture of a shifted
    // scene, upscaled 2x like a HAL digital zoom.
    auto make = [&](float dx, float dy, float noise) {
        std::normal_distribution<float> nd(0, noise);
        Frame f(w, h);
        std::vector<float> low(size_t(w / 2) * (h / 2));
        for (int y = 0; y < h / 2; ++y) {
            for (int x = 0; x < w / 2; ++x) {
                float acc = 0;
                for (int j = 0; j < 2; ++j)
                    for (int i = 0; i < 2; ++i) acc += s(2 * x + i + dx, 2 * y + j + dy);
                low[size_t(y) * (w / 2) + x] = acc / 4 + nd(rng);
            }
        }
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const float u = std::clamp((x + 0.5f) / 2 - 0.5f, 0.0f, w / 2 - 1.001f);
                const float v = std::clamp((y + 0.5f) / 2 - 0.5f, 0.0f, h / 2 - 1.001f);
                const int x0 = int(u), y0 = int(v);
                const float fx = u - x0, fy = v - y0;
                auto L = [&](int xx, int yy) { return low[size_t(yy) * (w / 2) + xx]; };
                f.y[size_t(y) * w + x] = clampU8((L(x0, y0) * (1 - fx) + L(x0 + 1, y0) * fx) * (1 - fy) +
                                                 (L(x0, y0 + 1) * (1 - fx) + L(x0 + 1, y0 + 1) * fx) * fy);
            }
        }
        std::fill(f.vu.begin(), f.vu.end(), 128);
        return f;
    };
    std::uniform_real_distribution<float> shift(-6, 6);
    std::vector<Frame> frames;
    frames.push_back(make(0, 0, 2));
    for (int i = 1; i < n; ++i) frames.push_back(make(shift(rng), shift(rng), 2));
    Frame truth(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) truth.y[size_t(y) * w + x] = clampU8(s(x + 0.0f, y + 0.0f));

    Params p;
    p.mode = Mode::SuperRes;
    p.toneEvStep = 0;
    p.reference = 0;
    Result r;
    std::string err;
    check(processBurst(frames, p, r, nullptr, &err), "super-res burst succeeds");
    if (r.referenceIndex != 0) {
        std::printf("  reference index %d; skipping PSNR comparison\n", r.referenceIndex);
        return;
    }
    const double single = psnrY(frames[0], truth, 24);
    const double sr = psnrY(r.image, truth, 24);
    std::printf("  PSNR vs ground truth: upscaled single %.2f dB, multi-frame SR %.2f dB\n", single, sr);
    check(sr > single + 0.25, "multi-frame super-res beats single-frame upscale");
}

void testToneMap() {
    std::mt19937 rng(4);
    const int w = 640, h = 480;
    Frame f = render(w, h, 0, 0, 1, rng, 0.45f);  // underexposed
    for (int y = 0; y < 120; ++y)
        for (int x = 0; x < w; ++x) f.y[size_t(y) * w + x] = 235;  // bright sky
    GrayF y(w, h), vu(w, h / 2);
    for (size_t i = 0; i < f.y.size(); ++i) y.px[i] = f.y[i];
    for (size_t i = 0; i < f.vu.size(); ++i) vu.px[i] = f.vu[i];
    ToneParams t;
    t.evStep = 1.0f;
    localToneMap(y, vu, t);
    double shadowBefore = 0, shadowAfter = 0, skyAfter = 0;
    int ns = 0, nk = 0;
    for (int r = 200; r < h; ++r)
        for (int c = 0; c < w; ++c) {
            shadowBefore += f.y[size_t(r) * w + c];
            shadowAfter += y.px[size_t(r) * w + c];
            ++ns;
        }
    for (int r = 10; r < 100; ++r)
        for (int c = 0; c < w; ++c) {
            skyAfter += y.px[size_t(r) * w + c];
            ++nk;
        }
    shadowBefore /= ns;
    shadowAfter /= ns;
    skyAfter /= nk;
    std::printf("  shadows %.1f -> %.1f, sky 235 -> %.1f\n", shadowBefore, shadowAfter, skyAfter);
    check(shadowAfter > shadowBefore * 1.3, "tone map lifts shadows");
    check(skyAfter < 250 && skyAfter > 215, "tone map keeps highlights below clipping");
}

void timing(int w, int h, int n, Mode mode, const char *label) {
    std::mt19937 rng(5);
    std::vector<Frame> frames;
    std::uniform_real_distribution<float> shift(-8, 8);
    for (int i = 0; i < n; ++i) frames.push_back(render(w, h, i ? shift(rng) : 0, i ? shift(rng) : 0, 6, rng));
    Params p;
    p.mode = mode;
    p.toneEvStep = mode == Mode::Astro ? 0 : 1.0f;
    Result r;
    std::string err;
    auto t = std::chrono::steady_clock::now();
    const bool ok = processBurst(frames, p, r, nullptr, &err);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
    std::printf("  %s %dx%d x%d: total %.0f ms (align %.0f, merge %.0f, finish %.0f)%s%s\n", label, w, h, n, ms,
                r.msAlign, r.msMerge, r.msFinish, ok ? "" : " FAILED: ", ok ? "" : err.c_str());
    check(ok, label);
}

}  // namespace

int main(int argc, char **argv) {
    const bool perf = argc > 1 && std::strcmp(argv[1], "--perf") == 0;
    testAlignment();
    testMergeSnrAndGhosts();
    testSuperRes();
    testToneMap();
    if (perf) {
        timing(4000, 3000, 8, Mode::Hdr, "HDR");
        timing(4000, 3000, 12, Mode::Night, "Night");
        timing(4000, 3000, 8, Mode::SuperRes, "SuperRes");
    }
    std::printf("%s (%d failure%s)\n", failures ? "FAILED" : "OK", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
