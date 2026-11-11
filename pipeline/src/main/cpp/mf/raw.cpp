/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "raw.h"

#include <cmath>
#include <vector>

#include "parallel.h"

namespace mf {

namespace {

constexpr int R = 0, G = 1, B = 2;
constexpr int kGammaLut = 4096;

// Colour of each position of the 2x2 CFA block in reading order.
void cfaLayout(Cfa cfa, int out[4]) {
    static const int layouts[4][4] = {{R, G, G, B}, {G, R, B, G}, {G, B, R, G}, {B, G, G, R}};
    for (int i = 0; i < 4; ++i) out[i] = layouts[int(cfa)][i];
}

float srgbOetf(float x) {
    x = std::clamp(x, 0.0f, 1.0f);
    return x <= 0.0031308f ? 12.92f * x : 1.055f * std::pow(x, 1 / 2.4f) - 0.055f;
}

}  // namespace

bool developRaw(const uint16_t *raw, int width, int height, int rowStrideBytes, const RawParams &p,
                Frame &out) {
    if (!raw || width < 4 || height < 4 || (width & 1) || (height & 1) || rowStrideBytes < width * 2) {
        return false;
    }
    if (int(p.cfa) < 0 || int(p.cfa) > 3) return false;
    int layout[4];
    cfaLayout(p.cfa, layout);
    const bool lsc = p.lsc && p.lscW >= 2 && p.lscH >= 2;

    // Normalised, shading-corrected, white-balanced and clipped mosaic.
    std::vector<float> m(size_t(width) * height);
    parallelFor(0, height, [&](int y) {
        const auto *row = reinterpret_cast<const uint16_t *>(reinterpret_cast<const uint8_t *>(raw) +
                                                              size_t(y) * rowStrideBytes);
        float gy = 0;
        int ly0 = 0, ly1 = 0;
        if (lsc) {
            gy = float(y) / (height - 1) * (p.lscH - 1);
            ly0 = std::min(int(gy), p.lscH - 2);
            ly1 = ly0 + 1;
            gy -= ly0;
        }
        for (int x = 0; x < width; ++x) {
            const int pos = (y & 1) * 2 + (x & 1);
            const int color = layout[pos];
            // Gains are indexed R, G_even (green on an even row), G_odd, B.
            const int gainIndex = color == R ? 0 : color == B ? 3 : ((y & 1) ? 2 : 1);
            float v = (float(row[x]) - p.black[pos]) / std::max(1.0f, p.white - p.black[pos]);
            if (lsc) {
                float gx = float(x) / (width - 1) * (p.lscW - 1);
                const int lx0 = std::min(int(gx), p.lscW - 2);
                gx -= lx0;
                auto at = [&](int lx, int ly) { return p.lsc[(size_t(ly) * p.lscW + lx) * 4 + gainIndex]; };
                v *= (at(lx0, ly0) * (1 - gx) + at(lx0 + 1, ly0) * gx) * (1 - gy) +
                     (at(lx0, ly1) * (1 - gx) + at(lx0 + 1, ly1) * gx) * gy;
            }
            m[size_t(y) * width + x] = std::clamp(v * p.wb[gainIndex], 0.0f, 1.0f);
        }
    });

    float gamma[kGammaLut + 1];
    for (int i = 0; i <= kGammaLut; ++i) gamma[i] = 255.0f * srgbOetf(float(i) / kGammaLut);
    auto tone = [&](float v) { return gamma[int(std::clamp(v, 0.0f, 1.0f) * kGammaLut)]; };

    auto px = [&](int x, int y) {
        x = x < 0 ? 1 : x >= width ? width - 2 : x;  // mirror keeps the CFA phase
        y = y < 0 ? 1 : y >= height ? height - 2 : y;
        return m[size_t(y) * width + x];
    };
    auto colorAt = [&](int x, int y) { return layout[(y & 1) * 2 + (x & 1)]; };

    out = Frame(width, height);
    parallelFor(0, height / 2, [&](int by) {
        for (int bx = 0; bx < width / 2; ++bx) {
            float cr = 0, cb = 0;
            for (int j = 0; j < 2; ++j) {
                for (int i = 0; i < 2; ++i) {
                    const int x = 2 * bx + i, y = 2 * by + j;
                    const int c = colorAt(x, y);
                    float rgb[3];
                    rgb[c] = px(x, y);
                    if (c == G) {
                        // Neighbours left/right share a colour, up/down the other one.
                        const int ch = colorAt(x + 1, y);
                        rgb[ch] = 0.5f * (px(x - 1, y) + px(x + 1, y));
                        rgb[2 - ch] = 0.5f * (px(x, y - 1) + px(x, y + 1));
                    } else {
                        rgb[G] = 0.25f * (px(x - 1, y) + px(x + 1, y) + px(x, y - 1) + px(x, y + 1));
                        rgb[2 - c] = 0.25f * (px(x - 1, y - 1) + px(x + 1, y - 1) + px(x - 1, y + 1) +
                                              px(x + 1, y + 1));
                    }
                    float s[3];
                    for (int k = 0; k < 3; ++k) {
                        s[k] = tone(p.gain * (p.ccm[3 * k] * rgb[0] + p.ccm[3 * k + 1] * rgb[1] +
                                              p.ccm[3 * k + 2] * rgb[2]));
                    }
                    out.y[size_t(y) * width + x] = clampU8(0.299f * s[0] + 0.587f * s[1] + 0.114f * s[2]);
                    cb += -0.168736f * s[0] - 0.331264f * s[1] + 0.5f * s[2];
                    cr += 0.5f * s[0] - 0.418688f * s[1] - 0.081312f * s[2];
                }
            }
            uint8_t *vu = &out.vu[size_t(by) * width + 2 * bx];
            vu[0] = clampU8(128 + cr * 0.25f);
            vu[1] = clampU8(128 + cb * 0.25f);
        }
    });
    return true;
}

}  // namespace mf
