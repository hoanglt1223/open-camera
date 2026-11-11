// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// Exemplar-based inpainting after Wexler, Shechtman and Irani (2007) with the randomized
// nearest-neighbour search of Barnes et al. (PatchMatch, 2009). Written from the papers.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <random>
#include <vector>

#include "retouch_core.h"

namespace retouch {
namespace {

using Clock = std::chrono::steady_clock;

struct Level {
    int w = 0;
    int h = 0;
    std::vector<float> c;  // RGB, 3 per pixel
    std::vector<uint8_t> hole;
};

Level downsample(const Level& s) {
    Level d;
    d.w = std::max(1, (s.w + 1) / 2);
    d.h = std::max(1, (s.h + 1) / 2);
    d.c.assign(size_t(d.w) * d.h * 3, 0.f);
    d.hole.assign(size_t(d.w) * d.h, 0);
    for (int y = 0; y < d.h; ++y) {
        for (int x = 0; x < d.w; ++x) {
            float acc[3] = {0, 0, 0};
            int known = 0;
            bool hole = false;
            for (int k = 0; k < 4; ++k) {
                int sx = std::min(2 * x + (k & 1), s.w - 1);
                int sy = std::min(2 * y + (k >> 1), s.h - 1);
                size_t i = size_t(sy) * s.w + sx;
                if (s.hole[i]) {
                    hole = true;
                    continue;
                }
                for (int ch = 0; ch < 3; ++ch) acc[ch] += s.c[i * 3 + ch];
                ++known;
            }
            size_t o = size_t(y) * d.w + x;
            d.hole[o] = hole ? 1 : 0;
            if (known) {
                for (int ch = 0; ch < 3; ++ch) d.c[o * 3 + ch] = acc[ch] / float(known);
            }
        }
    }
    return d;
}

// Integral image of the hole map, (w + 1) x (h + 1).
std::vector<int> integral(const Level& l) {
    std::vector<int> s(size_t(l.w + 1) * (l.h + 1), 0);
    for (int y = 0; y < l.h; ++y) {
        int row = 0;
        for (int x = 0; x < l.w; ++x) {
            row += l.hole[size_t(y) * l.w + x];
            s[size_t(y + 1) * (l.w + 1) + x + 1] = s[size_t(y) * (l.w + 1) + x + 1] + row;
        }
    }
    return s;
}

int boxSum(const std::vector<int>& s, int w, int x0, int y0, int x1, int y1) {
    // Inclusive bounds, already clamped.
    int W = w + 1;
    return s[size_t(y1 + 1) * W + x1 + 1] - s[size_t(y0) * W + x1 + 1] - s[size_t(y1 + 1) * W + x0] +
           s[size_t(y0) * W + x0];
}

// Onion-peel diffusion so the coarsest level starts from plausible colours.
void diffuseFill(Level& l) {
    std::vector<uint8_t> todo(l.hole);
    bool any = true;
    while (any) {
        any = false;
        std::vector<size_t> ready;
        for (int y = 0; y < l.h; ++y) {
            for (int x = 0; x < l.w; ++x) {
                size_t i = size_t(y) * l.w + x;
                if (!todo[i]) continue;
                any = true;
                bool near = (x > 0 && !todo[i - 1]) || (x + 1 < l.w && !todo[i + 1]) ||
                            (y > 0 && !todo[i - l.w]) || (y + 1 < l.h && !todo[i + l.w]);
                if (near) ready.push_back(i);
            }
        }
        if (any && ready.empty()) break;  // nothing known at all
        for (size_t i : ready) {
            int x = int(i % l.w), y = int(i / l.w);
            float acc[3] = {0, 0, 0};
            int n = 0;
            const int nx[4] = {x - 1, x + 1, x, x};
            const int ny[4] = {y, y, y - 1, y + 1};
            for (int k = 0; k < 4; ++k) {
                if (nx[k] < 0 || ny[k] < 0 || nx[k] >= l.w || ny[k] >= l.h) continue;
                size_t j = size_t(ny[k]) * l.w + nx[k];
                if (todo[j]) continue;
                for (int ch = 0; ch < 3; ++ch) acc[ch] += l.c[j * 3 + ch];
                ++n;
            }
            for (int ch = 0; ch < 3; ++ch) l.c[i * 3 + ch] = acc[ch] / float(n);
        }
        for (size_t i : ready) todo[i] = 0;
    }
}

class Solver {
public:
    Solver(Level& l, int r, std::mt19937& rng) : l_(l), r_(r), rng_(rng) {
        auto s = integral(l);
        valid_.assign(size_t(l.w) * l.h, 0);
        target_.clear();
        for (int y = 0; y < l.h; ++y) {
            for (int x = 0; x < l.w; ++x) {
                int x0 = std::max(0, x - r), y0 = std::max(0, y - r);
                int x1 = std::min(l.w - 1, x + r), y1 = std::min(l.h - 1, y + r);
                int holes = boxSum(s, l.w, x0, y0, x1, y1);
                size_t i = size_t(y) * l.w + x;
                if (holes > 0) target_.push_back(int(i));
                if (holes == 0 && x >= r && y >= r && x < l.w - r && y < l.h - r) {
                    valid_[i] = 1;
                    sources_.push_back(int(i));
                }
            }
        }
        nnf_.assign(size_t(l.w) * l.h, -1);
        dist_.assign(size_t(l.w) * l.h, 0.f);
    }

    bool hasSources() const { return !sources_.empty(); }
    std::vector<int>& nnf() { return nnf_; }
    bool valid(int x, int y) const {
        return x >= 0 && y >= 0 && x < l_.w && y < l_.h && valid_[size_t(y) * l_.w + x];
    }

    void initFrom(const Solver* coarse, const Level* coarseLevel) {
        std::uniform_int_distribution<int> pick(0, int(sources_.size()) - 1);
        for (int p : target_) {
            int s = -1;
            if (coarse) {
                int x = p % l_.w, y = p / l_.w;
                int cp = std::min(y / 2, coarseLevel->h - 1) * coarseLevel->w + std::min(x / 2, coarseLevel->w - 1);
                int cs = coarse->nnf_[size_t(cp)];
                if (cs >= 0) {
                    int sx = 2 * (cs % coarseLevel->w) + (x & 1);
                    int sy = 2 * (cs / coarseLevel->w) + (y & 1);
                    if (valid(sx, sy)) s = sy * l_.w + sx;
                }
            }
            if (s < 0) s = sources_[size_t(pick(rng_))];
            nnf_[size_t(p)] = s;
            dist_[size_t(p)] = distance(p, s, 1e30f);
        }
    }

    // One forward or backward PatchMatch sweep. Returns false when the deadline passed.
    bool search(bool forward, Clock::time_point deadline) {
        const int n = int(target_.size());
        const int step = forward ? 1 : -1;
        const int maxR = std::max(l_.w, l_.h);
        for (int k = 0; k < n; ++k) {
            if ((k & 1023) == 0 && Clock::now() > deadline) return false;
            int p = target_[size_t(forward ? k : n - 1 - k)];
            int x = p % l_.w, y = p / l_.w;
            int best = nnf_[size_t(p)];
            float bestD = dist_[size_t(p)];
            auto tryCand = [&](int sx, int sy) {
                if (!valid(sx, sy)) return;
                int s = sy * l_.w + sx;
                if (s == best) return;
                float d = distance(p, s, bestD);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            };
            // Propagation from the already visited neighbours.
            int nxp = x - step, nyp = y - step;
            if (nxp >= 0 && nxp < l_.w) {
                int q = nnf_[size_t(y) * l_.w + nxp];
                if (q >= 0) tryCand(q % l_.w + step, q / l_.w);
            }
            if (nyp >= 0 && nyp < l_.h) {
                int q = nnf_[size_t(nyp) * l_.w + x];
                if (q >= 0) tryCand(q % l_.w, q / l_.w + step);
            }
            // Random search around the current best at shrinking radii.
            int bx = best % l_.w, by = best / l_.w;
            for (int rad = maxR; rad >= 1; rad /= 2) {
                std::uniform_int_distribution<int> off(-rad, rad);
                tryCand(std::clamp(bx + off(rng_), 0, l_.w - 1), std::clamp(by + off(rng_), 0, l_.h - 1));
                bx = best % l_.w;
                by = best / l_.w;
            }
            nnf_[size_t(p)] = best;
            dist_[size_t(p)] = bestD;
        }
        return true;
    }

    // Each hole pixel becomes the weighted mean of the source pixels its covering patches vote.
    void vote() {
        const size_t n = size_t(l_.w) * l_.h;
        std::vector<float> acc(n * 3, 0.f), wsum(n, 0.f);
        const float area = float((2 * r_ + 1) * (2 * r_ + 1));
        std::vector<float> d;
        d.reserve(target_.size());
        for (int p : target_) d.push_back(dist_[size_t(p)] / area);
        float sigma2 = 1.f;
        if (!d.empty()) {
            auto mid = d.begin() + d.size() * 3 / 4;
            std::nth_element(d.begin(), mid, d.end());
            sigma2 = std::max(1.f, *mid);
        }
        for (int p : target_) {
            int s = nnf_[size_t(p)];
            if (s < 0) continue;
            float w = std::exp(-dist_[size_t(p)] / area / (2.f * sigma2));
            int px = p % l_.w, py = p / l_.w, sx = s % l_.w, sy = s / l_.w;
            for (int oy = -r_; oy <= r_; ++oy) {
                int qy = py + oy;
                if (qy < 0 || qy >= l_.h) continue;
                for (int ox = -r_; ox <= r_; ++ox) {
                    int qx = px + ox;
                    if (qx < 0 || qx >= l_.w) continue;
                    size_t q = size_t(qy) * l_.w + qx;
                    if (!l_.hole[q]) continue;
                    size_t si = (size_t(sy + oy) * l_.w + sx + ox) * 3;
                    acc[q * 3] += w * l_.c[si];
                    acc[q * 3 + 1] += w * l_.c[si + 1];
                    acc[q * 3 + 2] += w * l_.c[si + 2];
                    wsum[q] += w;
                }
            }
        }
        for (size_t q = 0; q < n; ++q) {
            if (!l_.hole[q] || wsum[q] <= 0) continue;
            for (int ch = 0; ch < 3; ++ch) l_.c[q * 3 + ch] = acc[q * 3 + ch] / wsum[q];
        }
        for (int p : target_) dist_[size_t(p)] = distance(p, nnf_[size_t(p)], 1e30f);
    }

private:
    float distance(int p, int s, float limit) const {
        int px = p % l_.w, py = p / l_.w, sx = s % l_.w, sy = s / l_.w;
        float d = 0;
        for (int oy = -r_; oy <= r_; ++oy) {
            int ty = std::clamp(py + oy, 0, l_.h - 1);
            const float* srow = &l_.c[(size_t(sy + oy) * l_.w + sx) * 3];
            const float* trow = &l_.c[size_t(ty) * l_.w * 3];
            for (int ox = -r_; ox <= r_; ++ox) {
                int tx = std::clamp(px + ox, 0, l_.w - 1);
                const float* t = trow + tx * 3;
                const float* sp = srow + ox * 3;
                float a = t[0] - sp[0], b = t[1] - sp[1], c = t[2] - sp[2];
                d += a * a + b * b + c * c;
            }
            if (d >= limit) return d;
        }
        return d;
    }

    Level& l_;
    int r_;
    std::mt19937& rng_;
    std::vector<uint8_t> valid_;
    std::vector<int> target_;
    std::vector<int> sources_;
    std::vector<int> nnf_;
    std::vector<float> dist_;
};

}  // namespace

int patchMatchInpaint(const Image& img, const uint8_t* mask, int patchRadius, double budgetMs) {
    if (img.w < 8 || img.h < 8 || mask == nullptr || patchRadius < 1) return kBadArgs;
    const auto deadline = Clock::now() + std::chrono::microseconds(int64_t(budgetMs * 1000));
    std::vector<Level> levels(1);
    bool anyHole = false;
    levels[0].w = img.w;
    levels[0].h = img.h;
    levels[0].c.resize(size_t(img.w) * img.h * 3);
    levels[0].hole.resize(size_t(img.w) * img.h);
    for (int y = 0; y < img.h; ++y) {
        const uint8_t* row = img.row(y);
        for (int x = 0; x < img.w; ++x) {
            size_t i = size_t(y) * img.w + x;
            for (int ch = 0; ch < 3; ++ch) levels[0].c[i * 3 + ch] = row[x * 4 + ch];
            levels[0].hole[i] = mask[i] ? 1 : 0;
            anyHole |= mask[i] != 0;
        }
    }
    if (!anyHole) return kOk;
    while (std::max(levels.back().w, levels.back().h) > 40) {
        Level next = downsample(levels.back());
        // Stop coarsening once the hole leaves no complete source patch.
        Level probe = next;
        std::mt19937 tmp(1);
        if (!Solver(probe, patchRadius, tmp).hasSources()) break;
        levels.push_back(std::move(next));
    }
    std::mt19937 rng(12345);
    diffuseFill(levels.back());
    std::unique_ptr<Solver> prev;
    for (int li = int(levels.size()) - 1; li >= 0; --li) {
        Level& l = levels[size_t(li)];
        if (li < int(levels.size()) - 1) {
            const Level& c = levels[size_t(li + 1)];
            for (int y = 0; y < l.h; ++y) {
                for (int x = 0; x < l.w; ++x) {
                    size_t i = size_t(y) * l.w + x;
                    if (!l.hole[i]) continue;
                    size_t j = size_t(std::min(y / 2, c.h - 1)) * c.w + std::min(x / 2, c.w - 1);
                    for (int ch = 0; ch < 3; ++ch) l.c[i * 3 + ch] = c.c[j * 3 + ch];
                }
            }
        }
        auto solver = std::make_unique<Solver>(l, patchRadius, rng);
        if (!solver->hasSources()) return kNoSource;
        solver->initFrom(prev.get(), li + 1 < int(levels.size()) ? &levels[size_t(li + 1)] : nullptr);
        const int em = li == int(levels.size()) - 1 ? 6 : (li == 0 ? 2 : 3);
        for (int e = 0; e < em; ++e) {
            for (int it = 0; it < 3; ++it) {
                if (!solver->search(it % 2 == 0, deadline)) return kTimeout;
            }
            solver->vote();
        }
        prev = std::move(solver);
    }
    if (Clock::now() > deadline) return kTimeout;
    // levels may have reallocated while the pyramid was built: take the base level again.
    const Level& base = levels[0];
    for (int y = 0; y < img.h; ++y) {
        uint8_t* row = img.row(y);
        for (int x = 0; x < img.w; ++x) {
            size_t i = size_t(y) * img.w + x;
            if (!base.hole[i]) continue;
            for (int ch = 0; ch < 3; ++ch) {
                row[x * 4 + ch] = uint8_t(std::clamp(base.c[i * 3 + ch], 0.f, 255.f) + 0.5f);
            }
        }
    }
    return kOk;
}

}  // namespace retouch
