/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include "align.h"

#include <cmath>
#include <cstdlib>
#include <limits>

#include "parallel.h"

namespace mf {

constexpr int Pyramid::kFactor[Pyramid::kLevels];

Gray downsampleBox(const Gray &src, int factor) {
    Gray dst(src.width / factor, src.height / factor);
    const int area = factor * factor;
    parallelFor(0, dst.height, [&](int y) {
        std::vector<uint32_t> acc(dst.width);
        for (int k = 0; k < factor; ++k) {
            const uint8_t *row = &src.px[size_t(y * factor + k) * src.width];
            for (int x = 0; x < dst.width; ++x) {
                uint32_t s = 0;
                for (int j = 0; j < factor; ++j) s += row[x * factor + j];
                acc[x] += s;
            }
        }
        uint8_t *out = &dst.px[size_t(y) * dst.width];
        for (int x = 0; x < dst.width; ++x) out[x] = uint8_t((acc[x] + area / 2) / area);
    });
    return dst;
}

Gray halfResLuma(const Frame &frame) {
    Gray full;
    full.width = frame.width;
    full.height = frame.height;
    full.px = frame.y;
    return downsampleBox(full, 2);
}

Pyramid buildPyramid(Gray level0) {
    Pyramid p;
    p.level[0] = std::move(level0);
    for (int l = 1; l < Pyramid::kLevels; ++l) {
        p.level[l] = downsampleBox(p.level[l - 1], Pyramid::kFactor[l]);
    }
    return p;
}

namespace {

struct IntD {
    int dx = 0;
    int dy = 0;
};

template <bool kL1>
uint32_t tileCost(const Gray &a, int ax, int ay, const Gray &b, int bx, int by, int n) {
    uint32_t sum = 0;
    bool inside = ax >= 0 && ay >= 0 && ax + n <= a.width && ay + n <= a.height && bx >= 0 &&
                  by >= 0 && bx + n <= b.width && by + n <= b.height;
    if (inside) {
        for (int y = 0; y < n; ++y) {
            const uint8_t *pa = &a.px[size_t(ay + y) * a.width + ax];
            const uint8_t *pb = &b.px[size_t(by + y) * b.width + bx];
            for (int x = 0; x < n; ++x) {
                int d = int(pa[x]) - int(pb[x]);
                sum += kL1 ? uint32_t(d < 0 ? -d : d) : uint32_t(d * d);
            }
        }
    } else {
        for (int y = 0; y < n; ++y) {
            for (int x = 0; x < n; ++x) {
                int d = int(a.at(ax + x, ay + y)) - int(b.at(bx + x, by + y));
                sum += kL1 ? uint32_t(d < 0 ? -d : d) : uint32_t(d * d);
            }
        }
    }
    return sum;
}

uint32_t cost(bool l1, const Gray &a, int ax, int ay, const Gray &b, int bx, int by, int n) {
    return l1 ? tileCost<true>(a, ax, ay, b, bx, by, n) : tileCost<false>(a, ax, ay, b, bx, by, n);
}

int nearestTile(float pos, const TileGrid &g, int count, int *neighbor) {
    float f = (pos - g.origin - g.size * 0.5f) / g.stride;
    int i = std::clamp(int(std::lround(f)), 0, count - 1);
    *neighbor = std::clamp(f > i ? i + 1 : i - 1, 0, count - 1);
    return i;
}

std::vector<IntD> searchLevel(const Gray &ref, const Gray &alt, const TileGrid &g,
                              const std::vector<IntD> *coarse, const TileGrid *cg, int factor,
                              int radius, bool l1) {
    std::vector<IntD> out(size_t(g.cols) * g.rows);
    parallelFor(0, g.rows, [&](int r) {
        for (int c = 0; c < g.cols; ++c) {
            const int x0 = g.origin + c * g.stride;
            const int y0 = g.origin + r * g.stride;
            IntD best;
            uint32_t bestCost = std::numeric_limits<uint32_t>::max();
            if (coarse) {
                int nx, ny;
                const int cx = nearestTile((x0 + g.size * 0.5f) / factor, *cg, cg->cols, &nx);
                const int cy = nearestTile((y0 + g.size * 0.5f) / factor, *cg, cg->rows, &ny);
                const int cand[3][2] = {{cx, cy}, {nx, cy}, {cx, ny}};
                for (const auto &k : cand) {
                    const IntD &d = (*coarse)[size_t(k[1]) * cg->cols + k[0]];
                    IntD s{d.dx * factor, d.dy * factor};
                    uint32_t v = cost(l1, ref, x0, y0, alt, x0 + s.dx, y0 + s.dy, g.size);
                    if (v < bestCost) {
                        bestCost = v;
                        best = s;
                    }
                }
            } else {
                bestCost = cost(l1, ref, x0, y0, alt, x0, y0, g.size);
            }
            const IntD center = best;
            for (int dy = -radius; dy <= radius; ++dy) {
                for (int dx = -radius; dx <= radius; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    IntD s{center.dx + dx, center.dy + dy};
                    uint32_t v = cost(l1, ref, x0, y0, alt, x0 + s.dx, y0 + s.dy, g.size);
                    if (v < bestCost) {
                        bestCost = v;
                        best = s;
                    }
                }
            }
            out[size_t(r) * g.cols + c] = best;
        }
    });
    return out;
}

float parabolaOffset(float cm, float c0, float cp) {
    float denom = cm - 2 * c0 + cp;
    if (denom <= 1e-6f) return 0;
    return std::clamp(0.5f * (cm - cp) / denom, -0.5f, 0.5f);
}

}  // namespace

std::vector<Displacement> alignTiles(const Pyramid &ref, const Pyramid &alt, const TileGrid &grid) {
    TileGrid grids[Pyramid::kLevels];
    grids[0] = grid;
    for (int l = 1; l < Pyramid::kLevels; ++l) {
        grids[l] = TileGrid::cover(ref.level[l].width, ref.level[l].height, l == 3 ? 8 : 16);
    }

    std::vector<IntD> field;
    for (int l = Pyramid::kLevels - 1; l >= 0; --l) {
        const bool finest = l == 0;
        const bool hasCoarse = l < Pyramid::kLevels - 1;
        field = searchLevel(ref.level[l], alt.level[l], grids[l], hasCoarse ? &field : nullptr,
                            hasCoarse ? &grids[l + 1] : nullptr,
                            hasCoarse ? Pyramid::kFactor[l + 1] : 1, finest ? 1 : 4, finest);
    }

    const Gray &r0 = ref.level[0];
    const Gray &a0 = alt.level[0];
    std::vector<Displacement> out(field.size());
    parallelFor(0, grid.rows, [&](int r) {
        for (int c = 0; c < grid.cols; ++c) {
            const size_t i = size_t(r) * grid.cols + c;
            const IntD d = field[i];
            const int x0 = grid.origin + c * grid.stride;
            const int y0 = grid.origin + r * grid.stride;
            auto l2 = [&](int dx, int dy) {
                return float(tileCost<false>(r0, x0, y0, a0, x0 + dx, y0 + dy, grid.size));
            };
            const float c0 = l2(d.dx, d.dy);
            out[i].dx = d.dx + parabolaOffset(l2(d.dx - 1, d.dy), c0, l2(d.dx + 1, d.dy));
            out[i].dy = d.dy + parabolaOffset(l2(d.dx, d.dy - 1), c0, l2(d.dx, d.dy + 1));
        }
    });
    return out;
}

double sharpness(const Gray &g) {
    double sum = 0;
    for (int y = 0; y + 1 < g.height; ++y) {
        const uint8_t *row = &g.px[size_t(y) * g.width];
        const uint8_t *next = row + g.width;
        for (int x = 0; x + 1 < g.width; ++x) {
            int gx = int(row[x + 1]) - row[x];
            int gy = int(next[x]) - row[x];
            sum += gx * gx + gy * gy;
        }
    }
    return sum / std::max(1, g.width * g.height);
}

}  // namespace mf
