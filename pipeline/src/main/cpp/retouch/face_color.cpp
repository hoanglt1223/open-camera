// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// Local colour corrections on face regions: red-eye, teeth whitening, eye brightening.

#include <algorithm>
#include <cmath>
#include <vector>

#include "retouch_core.h"

namespace retouch {
namespace {

float toLinear(float c) {
    c /= 255.f;
    return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
}

float toSrgb(float l) {
    l = std::clamp(l, 0.f, 1.f);
    float c = l <= 0.0031308f ? l * 12.92f : 1.055f * std::pow(l, 1.f / 2.4f) - 0.055f;
    return c * 255.f;
}

float labF(float t) { return t > 0.008856f ? std::cbrt(t) : 7.787f * t + 16.f / 116.f; }
float labFinv(float t) { return t > 0.206893f ? t * t * t : (t - 16.f / 116.f) / 7.787f; }

// sRGB (D65) <-> CIELAB.
void toLab(const uint8_t* p, float& L, float& a, float& b) {
    float r = toLinear(p[0]), g = toLinear(p[1]), bl = toLinear(p[2]);
    float x = (0.4124f * r + 0.3576f * g + 0.1805f * bl) / 0.95047f;
    float y = 0.2126f * r + 0.7152f * g + 0.0722f * bl;
    float z = (0.0193f * r + 0.1192f * g + 0.9505f * bl) / 1.08883f;
    float fx = labF(x), fy = labF(y), fz = labF(z);
    L = 116.f * fy - 16.f;
    a = 500.f * (fx - fy);
    b = 200.f * (fy - fz);
}

void fromLab(float L, float a, float b, uint8_t* p) {
    float fy = (L + 16.f) / 116.f;
    float fx = fy + a / 500.f;
    float fz = fy - b / 200.f;
    float x = labFinv(fx) * 0.95047f, y = labFinv(fy), z = labFinv(fz) * 1.08883f;
    float r = 3.2406f * x - 1.5372f * y - 0.4986f * z;
    float g = -0.9689f * x + 1.8758f * y + 0.0415f * z;
    float bl = 0.0557f * x - 0.2040f * y + 1.0570f * z;
    p[0] = uint8_t(toSrgb(r) + 0.5f);
    p[1] = uint8_t(toSrgb(g) + 0.5f);
    p[2] = uint8_t(toSrgb(bl) + 0.5f);
}

}  // namespace

int redEye(const Image& img, float cx, float cy, float radius) {
    if (radius < 1.f) return 0;
    int x0 = std::max(0, int(cx - radius)), x1 = std::min(img.w - 1, int(cx + radius));
    int y0 = std::max(0, int(cy - radius)), y1 = std::min(img.h - 1, int(cy + radius));
    int fixed = 0;
    for (int y = y0; y <= y1; ++y) {
        uint8_t* row = img.row(y);
        for (int x = x0; x <= x1; ++x) {
            float d = std::hypot(x + 0.5f - cx, y + 0.5f - cy) / radius;
            if (d >= 1.f) continue;
            uint8_t* p = row + x * 4;
            float r = p[0], g = p[1], b = p[2];
            float gb = 0.5f * (g + b);
            float redness = r / (gb + 1.f);
            if (redness < 1.6f || r < 50.f) continue;
            // Soft weight: stronger for redder pixels, fading over the outer 30 % of the circle.
            float w = std::clamp((redness - 1.6f) / 0.8f, 0.f, 1.f) * std::clamp((1.f - d) / 0.3f, 0.f, 1.f);
            float neutral = gb * 0.85f;
            p[0] = uint8_t(std::clamp(r + w * (neutral - r), 0.f, 255.f) + 0.5f);
            p[1] = uint8_t(std::clamp(g + w * (neutral - g), 0.f, 255.f) + 0.5f);
            p[2] = uint8_t(std::clamp(b + w * (neutral - b), 0.f, 255.f) + 0.5f);
            ++fixed;
        }
    }
    return fixed;
}

void whitenTeeth(const Image& img, const MaskView& mask, float strength) {
    int x0, y0, x1, y1;
    if (strength <= 0.f || !mask.bounds(img.w, img.h, x0, y0, x1, y1)) return;
    strength = std::clamp(strength, 0.f, 1.f);
    // Teeth are the bright, low-saturation part of the inner mouth: weight by L* above the
    // median of the masked area so lips, gums and the mouth cavity are left alone.
    std::vector<float> ls;
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            if (mask.at(x, y) < 0.5f) continue;
            float L, a, b;
            toLab(img.row(y) + x * 4, L, a, b);
            ls.push_back(L);
        }
    }
    if (ls.size() < 16) return;
    auto mid = ls.begin() + ls.size() / 2;
    std::nth_element(ls.begin(), mid, ls.end());
    const float lMed = *mid;
    for (int y = y0; y <= y1; ++y) {
        uint8_t* row = img.row(y);
        for (int x = x0; x <= x1; ++x) {
            float m = mask.at(x, y);
            if (m <= 0.f) continue;
            uint8_t* p = row + x * 4;
            float L, a, b;
            toLab(p, L, a, b);
            float bright = std::clamp((L - lMed + 8.f) / 16.f, 0.f, 1.f);
            float red = std::clamp((a - 18.f) / 10.f, 0.f, 1.f);  // gums and lips
            float w = m * bright * (1.f - red) * strength;
            if (w <= 0.f) continue;
            // Remove up to 60 % of the yellow, never below b* = 2 (pure grey looks fake).
            float b2 = b > 2.f ? b - w * 0.6f * (b - 2.f) : b;
            float L2 = std::min(96.f, L + w * 6.f * (1.f - L / 100.f) * 2.f);
            fromLab(L2, a * (1.f - 0.3f * w), b2, p);
        }
    }
}

void brightenEyes(const Image& img, const MaskView& mask, float strength) {
    int x0, y0, x1, y1;
    if (strength <= 0.f || !mask.bounds(img.w, img.h, x0, y0, x1, y1)) return;
    strength = std::clamp(strength, 0.f, 1.f);
    const int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
    std::vector<float> L(size_t(bw) * bh), A(L.size()), B(L.size()), blur(L.size());
    for (int y = 0; y < bh; ++y) {
        for (int x = 0; x < bw; ++x) {
            size_t i = size_t(y) * bw + x;
            toLab(img.row(y + y0) + (x + x0) * 4, L[i], A[i], B[i]);
        }
    }
    const int r = std::max(1, std::min(bw, bh) / 6);
    aicore::boxMean(L.data(), blur.data(), bw, bh, r);
    for (int y = 0; y < bh; ++y) {
        uint8_t* row = img.row(y + y0);
        for (int x = 0; x < bw; ++x) {
            float m = mask.at(x + x0, y + y0) * strength;
            if (m <= 0.f) continue;
            size_t i = size_t(y) * bw + x;
            float detail = L[i] - blur[i];
            float lifted = L[i] + m * (8.f * (1.f - L[i] / 100.f) + 0.6f * detail);
            fromLab(std::clamp(lifted, 0.f, 98.f), A[i], B[i], row + (x + x0) * 4);
        }
    }
}

}  // namespace retouch
