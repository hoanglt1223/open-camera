/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

// Native still renderer for looks. Mirrors LookMath.kt step for step so the
// saved photo matches the preview shader and the JVM reference.

#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace {

constexpr int kToneSize = 256;

struct Params {
    const float *lut;
    int n;
    const float *tone;
    float intensity;
    float grainAmount;
    float grainCell;
    float vignetteAmount;
    float vignetteSoftness;
    int width;
    int height;
};

inline float clamp01(float v) { return std::min(1.0f, std::max(0.0f, v)); }

inline void sampleLut(const Params &p, float r, float g, float b, float *out) {
    const int n = p.n;
    const float s = static_cast<float>(n - 1);
    const float fr = clamp01(r) * s, fg = clamp01(g) * s, fb = clamp01(b) * s;
    const int r0 = std::min(static_cast<int>(fr), n - 2);
    const int g0 = std::min(static_cast<int>(fg), n - 2);
    const int b0 = std::min(static_cast<int>(fb), n - 2);
    const float x = fr - r0, y = fg - g0, z = fb - b0;
    auto idx = [&](int dr, int dg, int db) { return (((b0 + db) * n + (g0 + dg)) * n + (r0 + dr)) * 3; };
    const int c000 = idx(0, 0, 0), c111 = idx(1, 1, 1);
    int c1, c2;
    float w0, w1, w2, w3;
    if (x >= y) {
        if (y >= z) { c1 = idx(1, 0, 0); c2 = idx(1, 1, 0); w0 = 1 - x; w1 = x - y; w2 = y - z; w3 = z; }
        else if (x >= z) { c1 = idx(1, 0, 0); c2 = idx(1, 0, 1); w0 = 1 - x; w1 = x - z; w2 = z - y; w3 = y; }
        else { c1 = idx(0, 0, 1); c2 = idx(1, 0, 1); w0 = 1 - z; w1 = z - x; w2 = x - y; w3 = y; }
    } else {
        if (x >= z) { c1 = idx(0, 1, 0); c2 = idx(1, 1, 0); w0 = 1 - y; w1 = y - x; w2 = x - z; w3 = z; }
        else if (y >= z) { c1 = idx(0, 1, 0); c2 = idx(0, 1, 1); w0 = 1 - y; w1 = y - z; w2 = z - x; w3 = x; }
        else { c1 = idx(0, 0, 1); c2 = idx(0, 1, 1); w0 = 1 - z; w1 = z - y; w2 = y - x; w3 = x; }
    }
    const float *d = p.lut;
    for (int c = 0; c < 3; ++c) {
        out[c] = w0 * d[c000 + c] + w1 * d[c1 + c] + w2 * d[c2 + c] + w3 * d[c111 + c];
    }
}

inline float tone(const float *t, float v) {
    const float pos = clamp01(v) * (kToneSize - 1);
    const int i = std::min(static_cast<int>(pos), kToneSize - 2);
    const float f = pos - i;
    return t[i] + (t[i + 1] - t[i]) * f;
}

inline float smoothstep(float e0, float e1, float x) {
    const float t = clamp01((x - e0) / (e1 - e0));
    return t * t * (3 - 2 * t);
}

inline uint32_t hash(int32_t cx, int32_t cy) {
    uint32_t h = static_cast<uint32_t>(cx) * 73856093u ^ static_cast<uint32_t>(cy) * 19349663u;
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return h;
}

inline int quantize(float v) { return std::min(255, std::max(0, static_cast<int>(v * 255.0f + 0.5f))); }

// RGBA_8888 rows [y0, y1).
void processRows(const Params &p, uint8_t *base, uint32_t stride, int y0, int y1) {
    const float k = p.intensity;
    const float w2 = static_cast<float>(p.width) * p.width;
    const float h2 = static_cast<float>(p.height) * p.height;
    float lut[3];
    for (int y = y0; y < y1; ++y) {
        uint8_t *px = base + static_cast<size_t>(y) * stride;
        const float ny = (y + 0.5f) / p.height * 2.0f - 1.0f;
        const int32_t cy = static_cast<int32_t>(std::floor(y / p.grainCell));
        for (int x = 0; x < p.width; ++x, px += 4) {
            const float r = px[0] / 255.0f, g = px[1] / 255.0f, b = px[2] / 255.0f;
            sampleLut(p, r, g, b, lut);
            float cr = r + (lut[0] - r) * k;
            float cg = g + (lut[1] - g) * k;
            float cb = b + (lut[2] - b) * k;
            cr += (tone(p.tone, cr) - cr) * k;
            cg += (tone(p.tone, cg) - cg) * k;
            cb += (tone(p.tone, cb) - cb) * k;
            if (p.vignetteAmount > 0.0f) {
                const float nx = (x + 0.5f) / p.width * 2.0f - 1.0f;
                const float dist = std::sqrt((nx * nx * w2 + ny * ny * h2) / (w2 + h2));
                const float v = 1.0f - p.vignetteAmount * k * smoothstep(1.0f - p.vignetteSoftness, 1.0f, dist);
                cr *= v; cg *= v; cb *= v;
            }
            if (p.grainAmount > 0.0f) {
                const int32_t cx = static_cast<int32_t>(std::floor(x / p.grainCell));
                const float n = (hash(cx, cy) >> 8) / 16777216.0f - 0.5f;
                const float l = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;
                const float gr = n * p.grainAmount * k * 0.4f * (1.0f - std::fabs(2.0f * l - 1.0f));
                cr += gr; cg += gr; cb += gr;
            }
            px[0] = static_cast<uint8_t>(quantize(clamp01(cr)));
            px[1] = static_cast<uint8_t>(quantize(clamp01(cg)));
            px[2] = static_cast<uint8_t>(quantize(clamp01(cb)));
        }
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_opencamera_pipeline_looks_NativeLookRenderer_nativeApply(
        JNIEnv *env, jobject /* this */, jobject bitmap, jfloatArray lut, jint lutSize,
        jfloatArray toneTable, jfloatArray scalars, jint stripRows, jint threads) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -2;
    if (env->GetArrayLength(lut) != lutSize * lutSize * lutSize * 3) return -3;
    if (env->GetArrayLength(toneTable) != kToneSize || env->GetArrayLength(scalars) != 6) return -4;

    std::vector<float> lutData(static_cast<size_t>(lutSize) * lutSize * lutSize * 3);
    env->GetFloatArrayRegion(lut, 0, static_cast<jsize>(lutData.size()), lutData.data());
    float toneData[kToneSize];
    env->GetFloatArrayRegion(toneTable, 0, kToneSize, toneData);
    float s[6];
    env->GetFloatArrayRegion(scalars, 0, 6, s);

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -5;

    const Params p{lutData.data(), lutSize, toneData, s[0], s[1], s[2], s[3], s[4],
                   static_cast<int>(info.width), static_cast<int>(info.height)};
    auto *base = static_cast<uint8_t *>(pixels);
    const int height = static_cast<int>(info.height);
    const int strip = std::max(1, static_cast<int>(stripRows));
    const int strips = (height + strip - 1) / strip;
    const int workers = std::max(1, std::min(static_cast<int>(threads), strips));

    // Workers pull fixed-height strips so per-thread working set stays bounded.
    std::atomic<int> next{0};
    auto work = [&]() {
        for (int i = next.fetch_add(1); i < strips; i = next.fetch_add(1)) {
            processRows(p, base, info.stride, i * strip, std::min(height, (i + 1) * strip));
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < workers; ++t) pool.emplace_back(work);
    work();
    for (auto &t : pool) t.join();

    AndroidBitmap_unlockPixels(env, bitmap);
    return 0;
}
