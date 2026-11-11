// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// Resampling half of the reshape tools. The displacement field (moving least squares from
// face landmarks, or liquify brushes) is computed on a coarse grid by the caller; this
// interpolates it per pixel and samples the source bilinearly.

#include <algorithm>
#include <cmath>

#include "../mf/parallel.h"
#include "retouch_core.h"

namespace retouch {

void warpGrid(const Image& src, const Image& dst, const float* dx, const float* dy, int gw, int gh) {
    if (dx == nullptr || dy == nullptr || gw < 2 || gh < 2 || src.w != dst.w || src.h != dst.h) return;
    const int w = src.w, h = src.h;
    const float sx = float(gw - 1) / float(std::max(1, w - 1));
    const float sy = float(gh - 1) / float(std::max(1, h - 1));
    mf::parallelFor(0, h, [&](int y) {
        uint32_t* out = reinterpret_cast<uint32_t*>(dst.row(y));
        float gy = y * sy;
        int gy0 = std::min(int(gy), gh - 2);
        float fy = gy - gy0;
        for (int x = 0; x < w; ++x) {
            float gx = x * sx;
            int gx0 = std::min(int(gx), gw - 2);
            float fx = gx - gx0;
            int i00 = gy0 * gw + gx0;
            int i10 = i00 + 1, i01 = i00 + gw, i11 = i01 + 1;
            float ddx = (dx[i00] * (1 - fx) + dx[i10] * fx) * (1 - fy) + (dx[i01] * (1 - fx) + dx[i11] * fx) * fy;
            float ddy = (dy[i00] * (1 - fx) + dy[i10] * fx) * (1 - fy) + (dy[i01] * (1 - fx) + dy[i11] * fx) * fy;
            if (std::fabs(ddx) < 1e-3f && std::fabs(ddy) < 1e-3f) {
                out[x] = reinterpret_cast<const uint32_t*>(src.row(y))[x];
                continue;
            }
            float px = std::clamp(x + ddx, 0.f, float(w - 1));
            float py = std::clamp(y + ddy, 0.f, float(h - 1));
            int x0 = int(px), y0 = int(py);
            int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
            float ax = px - x0, ay = py - y0;
            const uint8_t* p00 = src.row(y0) + x0 * 4;
            const uint8_t* p10 = src.row(y0) + x1 * 4;
            const uint8_t* p01 = src.row(y1) + x0 * 4;
            const uint8_t* p11 = src.row(y1) + x1 * 4;
            uint32_t v = 0;
            for (int c = 0; c < 4; ++c) {
                float a = p00[c] * (1 - ax) + p10[c] * ax;
                float b = p01[c] * (1 - ax) + p11[c] * ax;
                v |= uint32_t(std::clamp(a * (1 - ay) + b * ay + 0.5f, 0.f, 255.f)) << (8 * c);
            }
            out[x] = v;
        }
    });
}

}  // namespace retouch
