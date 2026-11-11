// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// Retouch pixel operations for the editor. Images are RGBA_8888 (R in the lowest byte) as in
// ai_core. Hole masks (uint8) cover the whole image; soft masks use MaskView. Functions never abort:
// they return 0 or a negative code, and throw only std::bad_alloc, which the JNI layer maps.

#pragma once

#include <cstdint>

#include "../ai/ai_core.h"

namespace retouch {

using aicore::Image;

// A soft mask (0..1) of mw x mh values stretched over the image rectangle [x, x + w) x
// [y, y + h); zero outside. Lets callers pass a small mask for a face region of a 12 MP photo.
struct MaskView {
    const float* m = nullptr;
    int mw = 0;
    int mh = 0;
    float x = 0, y = 0, w = 0, h = 0;

    float at(int px, int py) const {
        float u = (px + 0.5f - x) / w * mw - 0.5f;
        float v = (py + 0.5f - y) / h * mh - 0.5f;
        if (u < -0.5f || v < -0.5f || u > mw - 0.5f || v > mh - 0.5f) return 0.f;
        u = u < 0 ? 0 : (u > mw - 1 ? float(mw - 1) : u);
        v = v < 0 ? 0 : (v > mh - 1 ? float(mh - 1) : v);
        int x0 = int(u), y0 = int(v);
        int x1 = x0 + 1 < mw ? x0 + 1 : x0, y1 = y0 + 1 < mh ? y0 + 1 : y0;
        float fx = u - x0, fy = v - y0;
        float a = m[y0 * mw + x0] * (1 - fx) + m[y0 * mw + x1] * fx;
        float b = m[y1 * mw + x0] * (1 - fx) + m[y1 * mw + x1] * fx;
        return a * (1 - fy) + b * fy;
    }
    // Pixel bounds of the rectangle clipped to a w x h image; false when empty.
    bool bounds(int iw, int ih, int& x0, int& y0, int& x1, int& y1) const {
        x0 = x < 0 ? 0 : int(x);
        y0 = y < 0 ? 0 : int(y);
        x1 = int(x + w) + 1;
        y1 = int(y + h) + 1;
        if (x1 > iw - 1) x1 = iw - 1;
        if (y1 > ih - 1) y1 = ih - 1;
        return m != nullptr && mw > 0 && mh > 0 && w > 0 && h > 0 && x0 <= x1 && y0 <= y1;
    }
};

constexpr int kOk = 0;
constexpr int kBadArgs = -1;
constexpr int kNoMemory = -3;
constexpr int kTimeout = -4;
constexpr int kNoSource = -5;

// Exemplar inpainting (PatchMatch nearest-neighbour field + patch voting, coarse to fine).
// Fills pixels where mask != 0 in place from the rest of the image. [budgetMs] bounds the
// run; on timeout the image is left unchanged and kTimeout is returned.
int patchMatchInpaint(const Image& img, const uint8_t* mask, int patchRadius, double budgetMs);

// Healing: after a fill of [mask], removes the brightness/colour seam by adding the harmonic
// (membrane) interpolation of the boundary difference between [original] and [img]. In place
// on img; [original] is the image before the fill.
void healSeams(const Image& img, const Image& original, const uint8_t* mask);

// Two-band frequency separation inside [mask] (0..1): the band between a fine box blur and
// an edge-aware guided-filter base is attenuated by [strength]; pore texture below the fine
// radius is kept. [radius] is the guided filter radius in pixels.
void smoothSkin(const Image& img, const MaskView& mask, float strength, int radius);

// Red-eye: inside the circle, pixels with R / mean(G, B) above a threshold are replaced by a
// darkened neutral of their G/B, feathered by distance. Returns the corrected pixel count.
int redEye(const Image& img, float cx, float cy, float radius);

// Teeth whitening inside [mask]: lowers b* (yellow) and lifts L* in CIELAB, capped so the
// result keeps some warmth and never exceeds L* 96.
void whitenTeeth(const Image& img, const MaskView& mask, float strength);

// Eye brightening inside [mask]: L* lift plus local contrast; a* and b* are unchanged.
void brightenEyes(const Image& img, const MaskView& mask, float strength);

// Backward warp: dst(x, y) = src(x + dx, y + dy) with (dx, dy) bilinearly interpolated from a
// gw x gh displacement grid spanning the image corners. src and dst have the same size.
void warpGrid(const Image& src, const Image& dst, const float* dx, const float* dy, int gw, int gh);

}  // namespace retouch
