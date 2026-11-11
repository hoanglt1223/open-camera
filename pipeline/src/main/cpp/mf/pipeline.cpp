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

bool run(const std::vector<Frame> &frames, const Params &params, Result &result,
         const std::function<void(float)> &progress, std::string *error) {
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
    if (params.mode == Mode::SuperRes && n > 1) {
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
        return run(frames, params, result, progress, error);
    } catch (const std::bad_alloc &) {
        if (error) *error = "out of memory";
    } catch (const std::exception &e) {
        if (error) *error = e.what();
    }
    return false;
}

}  // namespace mf
