/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "pipeline.h"

#include <chrono>
#include <new>

#include "align.h"
#include "finish.h"
#include "merge.h"
#include "parallel.h"
#include "superres.h"

namespace mf {

namespace {

using Clock = std::chrono::steady_clock;

double msSince(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

MergeParams mergeParamsFor(Mode mode) {
    switch (mode) {
        case Mode::Night: return {2.0f, 5.5f};
        case Mode::Astro: return {2.5f, 7.0f};
        default: return {1.5f, 4.0f};
    }
}

GrayF boxDownLuma(const GrayF &y, int f) {
    GrayF d(y.width / f, y.height / f);
    const float inv = 1.0f / (f * f);
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

Frame cropFrame(const Frame &f, int x0, int y0, int w, int h) {
    Frame c(w, h);
    for (int r = 0; r < h; ++r) {
        std::copy_n(&f.y[size_t(y0 + r) * f.width + x0], w, &c.y[size_t(r) * w]);
    }
    for (int r = 0; r < h / 2; ++r) {
        std::copy_n(&f.vu[size_t(y0 / 2 + r) * f.width + x0], w, &c.vu[size_t(r) * w]);
    }
    return c;
}

bool run(const std::vector<Frame> &frames, const Params &params, Result &result,
         const std::function<void(float)> &progress, std::string *error, int outW = 0, int outH = 0) {
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
    auto t0 = Clock::now();

    std::vector<Pyramid> pyramids(n);
    for (int i = 0; i < n; ++i) pyramids[i] = buildPyramid(halfResLuma(frames[i]));

    int ref = params.reference >= 0 && params.reference < n ? params.reference : 0;
    double best = -1;
    for (int i = 0; i < std::min(n, 3) && params.reference < 0; ++i) {
        const double s = sharpness(pyramids[i].level[1]);
        if (s > best) {
            best = s;
            ref = i;
        }
    }

    const TileGrid grid = TileGrid::cover(W / 2, H / 2, 16);
    std::vector<std::vector<Displacement>> disp(n);
    for (int i = 0; i < n; ++i) {
        if (i != ref) disp[i] = alignTiles(pyramids[ref], pyramids[i], grid);
        if (progress) progress(0.1f + 0.4f * (i + 1) / n);
    }
    result.msAlign = msSince(t0);

    auto t1 = Clock::now();
    std::vector<const Frame *> fp(n);
    std::vector<const Gray *> hp(n);
    for (int i = 0; i < n; ++i) {
        fp[i] = &frames[i];
        hp[i] = &pyramids[i].level[0];
    }
    GrayF y, vu;
    MergeStats stats;
    mergeFrames(fp, hp, ref, disp, grid, mergeParamsFor(params.mode), y, vu, &stats);
    const bool scaled = outW > 0 && outH > 0 && (outW != W || outH != H);
    if (scaled) {
        if (params.mode == Mode::SuperRes && n > 1) {
            std::vector<Gray> full(n);
            std::vector<const Gray *> fullp(n);
            for (int i = 0; i < n; ++i) {
                full[i] = Gray(W, H);
                full[i].px = frames[i].y;
                fullp[i] = &full[i];
            }
            superResolveCrop(fullp, ref, disp, grid, stats.noiseSigma, float(outW) / W, outW, outH, y);
        } else {
            GrayF up;
            resizeBilinear(y, 1, outW, outH, up);
            y = std::move(up);
        }
        GrayF vuUp;
        resizeBilinear(vu, 2, outW / 2, outH / 2, vuUp);
        vu = std::move(vuUp);
    } else if (params.mode == Mode::SuperRes && n > 1) {
        superResolveLuma(hp, ref, disp, grid, stats.noiseSigma, y);
    }
    result.msMerge = msSince(t1);
    if (progress) progress(0.75f);

    auto t2 = Clock::now();
    ToneParams tone;
    tone.evStep = params.toneEvStep;
    switch (params.mode) {
        case Mode::Hdr:
            chromaDenoise(vu, 0.5f);
            break;
        case Mode::Night:
            suppressHotPixels(y, 40.0f);
            chromaDenoise(vu, 1.0f);
            break;
        case Mode::Astro:
            suppressHotPixels(y, 24.0f);
            chromaDenoise(vu, 1.5f);
            break;
        case Mode::SuperRes:
            sharpen(y, 1.2f);
            chromaDenoise(vu, 0.5f);
            break;
    }
    if (tone.evStep > 0) result.toneBase = boxDownLuma(y, kToneBaseFactor);
    localToneMap(y, vu, tone);
    toFrame(y, vu, result.image);
    result.msFinish = msSince(t2);

    result.referenceIndex = ref;
    result.noiseSigma = stats.noiseSigma;
    result.meanWeight = stats.meanWeight;
    if (progress) progress(1.0f);
    return true;
}

}  // namespace

bool processBurst(const std::vector<Frame> &frames, const Params &params, Result &result,
                  const std::function<void(float)> &progress, std::string *error) {
    try {
        if (params.cropW <= 0 || frames.empty()) return run(frames, params, result, progress, error);
        const int W = frames[0].width, H = frames[0].height;
        const int x0 = std::clamp(params.cropX, 0, W - 2) & ~1;
        const int y0 = std::clamp(params.cropY, 0, H - 2) & ~1;
        const int cw = std::min(params.cropW, W - x0) & ~1;
        const int ch = std::min(params.cropH, H - y0) & ~1;
        if (cw < 64 || ch < 64) {
            if (error) *error = "crop too small";
            return false;
        }
        std::vector<Frame> crops;
        crops.reserve(frames.size());
        for (const Frame &f : frames) {
            if (f.width != W || f.height != H) {
                if (error) *error = "inconsistent frame sizes";
                return false;
            }
            crops.push_back(cropFrame(f, x0, y0, cw, ch));
        }
        return run(crops, params, result, progress, error, W, H);
    } catch (const std::bad_alloc &) {
        if (error) *error = "out of memory";
    } catch (const std::exception &e) {
        if (error) *error = e.what();
    }
    return false;
}

}  // namespace mf
