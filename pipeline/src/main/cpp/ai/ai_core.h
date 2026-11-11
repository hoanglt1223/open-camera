// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0

#pragma once

#include <cstdint>

namespace aicore {

// RGBA_8888 pixels (R in the lowest byte), rows [stride] bytes apart.
struct Image {
    uint8_t* pixels;
    int w;
    int h;
    int stride;
    uint8_t* row(int y) const { return pixels + static_cast<long>(y) * stride; }
};

// Edge-aware mask refinement: guided filter with the image luma as guide. In place.
void refineMask(const Image& guide, float* mask, int radius, float eps);

// Lens blur: disc kernel of [radius] px over pixels weighted by (1 - sharpness), in linear
// light with a highlight boost so bright spots bloom. Subject colours never bleed into the
// background because the convolution is normalized by the same weights.
void lensBlur(const Image& src, const float* sharpness, float radius, const Image& out);

// full = full * a + up(blurred) * (1 - a), with a and blurred bilinearly upsampled.
void composite(const Image& full, const Image& blurred, const float* sharpness);

// Frequency-separation skin smoothing: removes mid-frequency blemishes inside [mask]
// (mw x mh over the whole image) while keeping pore-level texture, plus a slight lift.
void smoothSkin(const Image& full, const float* mask, int mw, int mh, float strength);

// Classical denoise: guided filter on luma, luma-guided chroma smoothing. Strip based.
void denoise(const Image& full, float strength);

// Unsharp mask on luma. Strip based.
void sharpen(const Image& full, float amount);

// Helpers exposed for tests.
void boxMean(const float* src, float* dst, int w, int h, int r);
void guidedFilter(const float* I, const float* p, float* q, int w, int h, int r, float eps);

}  // namespace aicore
