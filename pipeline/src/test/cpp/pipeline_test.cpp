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
#include "raw.h"
#include "rawmerge.h"
#include "stack.h"
#include "superres.h"

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
    std::printf("  HAL-zoomed input: upscaled single %.2f dB, multi-frame %.2f dB\n", single, sr);
    // Fallback path only (the HAL already interpolated the frames): it must not lose detail.
    check(sr > single, "super-res on HAL-zoomed frames is not worse than a single frame");
}

// Super Res on an unzoomed burst: native sensor frames, zoom crop after alignment and merge.
void testSuperResCrop() {
    std::mt19937 rng(3);
    const int w = 800, h = 600, n = 8;
    const int cx = w / 4, cy = h / 4, cw = w / 2, ch = h / 2;  // 2x zoom on the centre
    Scene s;
    // Scene units are half a sensor pixel, so the scene holds detail beyond the sensor's Nyquist.
    auto sensor = [&](float dx, float dy, float noise) {
        std::normal_distribution<float> nd(0, noise);
        Frame f(w, h);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                float acc = 0;
                for (int j = 0; j < 4; ++j)
                    for (int i = 0; i < 4; ++i) acc += s(2 * (x + dx) + 0.25f + 0.5f * i, 2 * (y + dy) + 0.25f + 0.5f * j);
                f.y[size_t(y) * w + x] = clampU8(acc / 16 + nd(rng));
            }
        }
        std::fill(f.vu.begin(), f.vu.end(), 128);
        return f;
    };
    std::uniform_real_distribution<float> shift(-6, 6);
    std::vector<Frame> frames;
    frames.push_back(sensor(0, 0, 2));
    for (int i = 1; i < n; ++i) frames.push_back(sensor(shift(rng), shift(rng), 2));
    // Output pixel (ox, oy) covers half a sensor pixel of the crop.
    Frame truth(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float acc = 0;
            for (int j = 0; j < 2; ++j)
                for (int i = 0; i < 2; ++i) acc += s(2 * cx + x + 0.25f + 0.5f * i, 2 * cy + y + 0.25f + 0.5f * j);
            truth.y[size_t(y) * w + x] = clampU8(acc / 4);
        }

    Params p;
    p.mode = Mode::SuperRes;
    p.toneEvStep = 0;
    p.reference = 0;
    p.cropX = cx;
    p.cropY = cy;
    p.cropW = cw;
    p.cropH = ch;
    Result r;
    std::string err;
    const bool ok = processBurst(frames, p, r, nullptr, &err);
    check(ok && r.image.width == w && r.image.height == h, "super-res crop burst succeeds at full size");
    if (!ok || r.referenceIndex != 0) return;

    GrayF ref(cw, ch), up;
    for (int y = 0; y < ch; ++y)
        for (int x = 0; x < cw; ++x) ref.px[size_t(y) * cw + x] = frames[0].y[size_t(cy + y) * w + cx + x];
    resizeBilinear(ref, 1, w, h, up);
    Frame single(w, h);
    for (size_t i = 0; i < single.y.size(); ++i) single.y[i] = clampU8(up.px[i]);
    const double ps = psnrY(single, truth, 24), psr = psnrY(r.image, truth, 24);
    std::printf("  unzoomed burst, 2x crop: single upscale %.2f dB, multi-frame SR %.2f dB (+%.2f)\n", ps, psr,
                psr - ps);
    check(psr > ps + 1.0, "multi-frame super-res beats single-frame upscale by more than 1 dB");
}

void testRawDevelop() {
    // Flat grey card under a warm light: camera RGB = grey * (1/wb). After development it must be
    // neutral, at the expected sRGB level, with no demosaic artefacts on a flat field.
    const int w = 64, h = 48;
    const float white = 1023, black = 64, grey = 0.18f;
    RawParams p;
    p.cfa = Cfa::Rggb;
    p.wb[0] = 2.0f;
    p.wb[1] = p.wb[2] = 1.0f;
    p.wb[3] = 1.6f;
    for (float &b : p.black) b = black;
    p.white = white;
    std::vector<uint16_t> raw(size_t(w) * h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const int pos = (y & 1) * 2 + (x & 1);
            const float g = pos == 0 ? grey / 2.0f : pos == 3 ? grey / 1.6f : grey;
            raw[size_t(y) * w + x] = uint16_t(std::lround(black + g * (white - black)));
        }
    Frame out;
    check(developRaw(raw.data(), w, h, w * 2, p, out), "raw development succeeds");
    int maxChroma = 0, minY = 255, maxY = 0;
    for (uint8_t v : out.vu) maxChroma = std::max(maxChroma, std::abs(int(v) - 128));
    for (uint8_t v : out.y) {
        minY = std::min<int>(minY, v);
        maxY = std::max<int>(maxY, v);
    }
    // sRGB(0.18) = 118.
    std::printf("  raw grey card: Y %d..%d, max chroma offset %d\n", minY, maxY, maxChroma);
    check(maxChroma <= 1 && minY >= 116 && maxY <= 120, "white-balanced grey develops to neutral sRGB 118");

    // Saturated colour patch keeps its hue through the colour matrix (identity here): pure red.
    std::vector<uint16_t> red(size_t(w) * h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) red[size_t(y) * w + x] = uint16_t((y & 1) == 0 && (x & 1) == 0 ? 900 : 64);
    p.wb[0] = p.wb[1] = p.wb[2] = p.wb[3] = 1.0f;
    check(developRaw(red.data(), w, h, w * 2, p, out), "raw development of a red patch succeeds");
    const int cr = out.vu[size_t(10) * w + 20], cb = out.vu[size_t(10) * w + 21];
    std::printf("  raw red patch: Cr %d Cb %d\n", cr, cb);
    check(cr > 200 && cb < 110, "red stays red after demosaic");
}


void testRawMerge() {
    // Bayer burst of one scene, shifted by whole CFA blocks between frames, with sensor noise.
    // Frame 5 has a bright block the others do not: the merge must not ghost it in.
    const int w = 512, h = 384, n = 8;
    const float black = 64, white = 1023, sigma = 12;
    Scene scene;
    std::mt19937 rng(11);
    std::normal_distribution<float> noise(0.0f, sigma);
    auto truth = [&](int x, int y) { return std::clamp(scene(float(x), float(y)) * 2.5f, 0.0f, 900.0f); };
    auto frame = [&](int sx, int sy, bool ghost) {
        std::vector<uint16_t> raw(size_t(w) * h);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                float v = truth(x + sx, y + sy);
                if (ghost && x >= 200 && x < 264 && y >= 160 && y < 224) v = 850;
                raw[size_t(y) * w + x] = uint16_t(std::clamp(black + v + noise(rng), 0.0f, white));
            }
        return raw;
    };
    const float blacks[4] = {black, black, black, black};
    RawMergeParams p;
    p.white = white;
    RawMerger merger;
    std::string error;
    const std::vector<uint16_t> ref = frame(0, 0, false);
    check(merger.begin(w, h, p, ref.data(), w * 2, blacks, &error), "raw merge starts");
    const int shifts[n][2] = {{0, 0}, {2, 0}, {0, 2}, {-2, 2}, {4, -2}, {2, 2}, {-4, 0}, {0, -4}};
    float ghostWeight = 1;
    for (int i = 1; i < n; ++i) {
        const std::vector<uint16_t> f = frame(shifts[i][0], shifts[i][1], i == 5);
        float weight = 0;
        check(merger.add(f.data(), w * 2, blacks, &weight, &error), "raw frame aligns and merges");
        if (i == 5) ghostWeight = weight;
    }
    check(merger.finish(&error), "raw merge finishes");
    const float scale = 65535.0f / (white - black);
    double errSingle = 0, errMerged = 0;
    int count = 0;
    for (int y = 32; y < h - 32; ++y)
        for (int x = 32; x < w - 32; ++x) {
            if (x >= 180 && x < 284 && y >= 140 && y < 244) continue;
            const double t = truth(x, y);
            const double single = (double(ref[size_t(y) * w + x]) - black) - t;
            const double merged = merger.mosaic()[size_t(y) * w + x] / scale - t;
            errSingle += single * single;
            errMerged += merged * merged;
            ++count;
        }
    const double rmsSingle = std::sqrt(errSingle / count), rmsMerged = std::sqrt(errMerged / count);
    double ghost = 0;
    for (int y = 176; y < 208; ++y)
        for (int x = 216; x < 248; ++x) ghost = std::max(ghost, std::abs(double(merger.mosaic()[size_t(y) * w + x] / scale - truth(x, y))));
    std::printf("  raw merge: rms single %.2f merged %.2f, ghost frame weight %.2f, max ghost error %.1f\n",
                rmsSingle, rmsMerged, ghostWeight, ghost);
    check(rmsMerged < rmsSingle / 2.0, "merged mosaic halves the noise of one frame");
    check(ghost < 6 * sigma, "moving block is not merged in");

    std::vector<uint16_t> rgb(size_t(w) * 3 * 4);
    check(merger.demosaicRows(100, 4, rgb.data()), "merged mosaic demosaics");
    check(!merger.demosaicRows(h - 2, 4, rgb.data()), "rows past the end are rejected");
    RawMerger empty;
    check(!empty.add(ref.data(), w * 2, blacks, nullptr, &error), "add before begin is rejected");
    p.white = 10;
    check(!empty.begin(w, h, p, ref.data(), w * 2, blacks, &error), "white below black is rejected");
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

void timing(int w, int h, int n, Mode mode, const char *label, float zoom = 1.0f) {
    std::mt19937 rng(5);
    std::vector<Frame> frames;
    std::uniform_real_distribution<float> shift(-8, 8);
    for (int i = 0; i < n; ++i) frames.push_back(render(w, h, i ? shift(rng) : 0, i ? shift(rng) : 0, 6, rng));
    Params p;
    p.mode = mode;
    p.toneEvStep = mode == Mode::Astro ? 0 : 1.0f;
    if (zoom > 1.0f) {
        p.cropW = int(w / zoom);
        p.cropH = int(h / zoom);
        p.cropX = (w - p.cropW) / 2;
        p.cropY = (h - p.cropH) / 2;
    }
    Result r;
    std::string err;
    auto t = std::chrono::steady_clock::now();
    const bool ok = processBurst(frames, p, r, nullptr, &err);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
    std::printf("  %s %dx%d x%d: total %.0f ms (align %.0f, merge %.0f, finish %.0f)%s%s\n", label, w, h, n, ms,
                r.msAlign, r.msMerge, r.msFinish, ok ? "" : " FAILED: ", ok ? "" : err.c_str());
    check(ok, label);
}

Frame blurred(const Frame &f, float sigma) {
    GrayF g(f.width, f.height), out;
    for (size_t i = 0; i < f.y.size(); ++i) g.px[i] = f.y[i];
    gaussianBlur(g, out, sigma);
    Frame r = f;
    for (size_t i = 0; i < r.y.size(); ++i) r.y[i] = clampU8(out.px[i]);
    return r;
}

Frame addNoise(Frame f, float sigma, std::mt19937 &rng) {
    std::normal_distribution<float> n(0, sigma);
    for (auto &v : f.y) v = clampU8(v + n(rng));
    return f;
}

void testStackOps() {
    std::mt19937 rng(5);
    const int w = 480, h = 360, n = 7;
    Frame clean = render(w, h, 0, 0, 0, rng);
    std::vector<Frame> frames;
    for (int i = 0; i < n; ++i) {
        Frame f = render(w, h, 0, 0, 8, rng);
        // A walker that is somewhere else in every frame.
        for (int y = 100; y < 160; ++y)
            for (int x = 40 + 60 * i; x < 80 + 60 * i; ++x) f.y[size_t(y) * w + x] = 245;
        frames.push_back(std::move(f));
    }
    StackParams p;
    p.align = false;
    StackResult r;
    std::string err;
    p.op = StackOp::Median;
    check(stackBurst(frames, p, r, &err), "median stack succeeds");
    double ghost = 0;
    for (int y = 100; y < 160; ++y)
        for (int x = 40; x < 80 + 60 * (n - 1); ++x)
            ghost += std::fabs(double(r.image.y[size_t(y) * w + x]) - clean.y[size_t(y) * w + x]);
    ghost /= 60.0 * (40 + 60 * (n - 1));
    std::printf("  median: mean abs error on the walker path %.2f\n", ghost);
    check(ghost < 8.0, "median stack removes a passer-by");

    p.op = StackOp::Mean;
    std::vector<Frame> still;
    for (int i = 0; i < n; ++i) still.push_back(render(w, h, 0, 0, 8, rng));
    check(stackBurst(still, p, r, &err), "mean stack succeeds");
    const double single = psnrY(still[0], clean, 8), mean = psnrY(r.image, clean, 8);
    std::printf("  mean: PSNR single %.2f dB, stacked %.2f dB\n", single, mean);
    check(mean - single > 4.0, "mean stack averages noise like a long exposure");

    p.op = StackOp::Max;
    check(stackBurst(frames, p, r, &err), "max stack succeeds");
    int lit = 0;
    for (int i = 0; i < n; ++i) lit += r.image.y[size_t(130) * w + 60 + 60 * i] > 235;
    check(lit == n, "max stack keeps every position of a moving light (trail)");
}

void testFocusStack() {
    std::mt19937 rng(6);
    const int w = 480, h = 360;
    Frame clean = render(w, h, 0, 0, 0, rng);
    Frame soft = blurred(clean, 3.0f);
    Frame a = clean, b = clean;
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const size_t i = size_t(y) * w + x;
            (x < w / 2 ? b : a).y[i] = soft.y[i];
        }
    std::vector<Frame> frames{addNoise(a, 2, rng), addNoise(b, 2, rng)};
    StackParams p;
    p.op = StackOp::Focus;
    p.align = false;
    StackResult r;
    std::string err;
    check(stackBurst(frames, p, r, &err), "focus stack succeeds");
    const double pa = psnrY(frames[0], clean, 8), pb = psnrY(frames[1], clean, 8), ps = psnrY(r.image, clean, 8);
    std::printf("  focus: PSNR near %.2f, far %.2f, stacked %.2f dB\n", pa, pb, ps);
    check(ps > std::max(pa, pb) + 3.0, "focus stack is sharper than either source frame");
}

void testMoon() {
    std::mt19937 rng(7);
    const int w = 400, h = 400, r0 = 120;
    Scene s;
    Frame truth(w, h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const float d = std::hypot(x - 200.0f, y - 200.0f);
            truth.y[size_t(y) * w + x] = d < r0 ? clampU8(0.9f * s(x * 0.7f, y * 0.7f)) : 6;
        }
    std::fill(truth.vu.begin(), truth.vu.end(), 128);
    // Atmospheric seeing: frames blurred by different amounts, a few lucky ones nearly sharp.
    std::vector<Frame> frames;
    const float seeing[] = {2.6f, 1.3f, 2.2f, 3.0f, 1.2f, 2.4f, 2.8f, 1.4f, 2.0f, 2.5f};
    for (float sg : seeing) frames.push_back(addNoise(blurred(truth, sg), 6, rng));
    StackParams p;
    p.op = StackOp::Moon;
    p.align = false;
    p.keepFraction = 0.3f;
    StackResult r;
    std::string err;
    check(stackBurst(frames, p, r, &err), "moon stack succeeds");
    check(r.framesUsed == 3, "lucky imaging keeps the sharpest 30%");
    double best = 0, avg = 0;
    for (auto &f : frames) {
        best = std::max(best, psnrY(f, truth, 20));
        avg += psnrY(f, truth, 20) / frames.size();
    }
    const double out = psnrY(r.image, truth, 20);
    std::printf("  moon: PSNR average frame %.2f, best frame %.2f, result %.2f dB\n", avg, best, out);
    check(out > best, "moon result beats the best single frame");
    int clipped = 0;
    for (uint8_t v : r.image.y) clipped += v >= 255;
    check(clipped < w * h / 1000, "moon result is not clipped");
}

}  // namespace

int main(int argc, char **argv) {
    const bool perf = argc > 1 && std::strcmp(argv[1], "--perf") == 0;
    testAlignment();
    testMergeSnrAndGhosts();
    testSuperRes();
    testSuperResCrop();
    testRawDevelop();
    testRawMerge();
    testToneMap();
    testStackOps();
    testFocusStack();
    testMoon();
    if (perf) {
        timing(4000, 3000, 8, Mode::Hdr, "HDR");
        timing(4000, 3000, 12, Mode::Night, "Night");
        timing(4000, 3000, 8, Mode::SuperRes, "SuperRes");
        timing(4000, 3000, 8, Mode::SuperRes, "SuperRes 2x crop", 2.0f);
    }
    std::printf("%s (%d failure%s)\n", failures ? "FAILED" : "OK", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
