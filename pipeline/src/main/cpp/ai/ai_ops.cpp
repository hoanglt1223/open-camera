// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// Pixel operations behind the ML features: mask refinement (guided filter), lens blur with a
// disc kernel, compositing, skin smoothing, classical denoise and sharpening. All entry points
// return an error code instead of aborting; 0 means success.

#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

#include "ai_core.h"

namespace {

constexpr int kOk = 0;
constexpr int kBadArgs = -1;
constexpr int kLockFailed = -2;
constexpr int kNoMemory = -3;

struct Locked {
    JNIEnv* env;
    jobject bitmap;
    AndroidBitmapInfo info{};
    uint8_t* pixels = nullptr;
    bool ok = false;

    Locked(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (b == nullptr || AndroidBitmap_getInfo(e, b, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
        void* p = nullptr;
        if (AndroidBitmap_lockPixels(e, b, &p) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        pixels = static_cast<uint8_t*>(p);
        ok = true;
    }
    ~Locked() {
        if (ok) AndroidBitmap_unlockPixels(env, bitmap);
    }
    aicore::Image image() const {
        return {pixels, static_cast<int>(info.width), static_cast<int>(info.height), static_cast<int>(info.stride)};
    }
};

struct FloatArray {
    JNIEnv* env;
    jfloatArray array;
    float* data = nullptr;
    jsize size = 0;
    FloatArray(JNIEnv* e, jfloatArray a) : env(e), array(a) {
        if (a == nullptr) return;
        size = e->GetArrayLength(a);
        data = e->GetFloatArrayElements(a, nullptr);
    }
    ~FloatArray() {
        if (data) env->ReleaseFloatArrayElements(array, data, 0);
    }
};

template <class F>
int guarded(F f) {
    try {
        return f();
    } catch (const std::bad_alloc&) {
        return kNoMemory;
    } catch (...) {
        return kBadArgs;
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_ai_NativeAi_refineMask(
    JNIEnv* env, jclass, jobject guide, jfloatArray mask, jint radius, jfloat eps) {
    return guarded([&] {
        Locked g(env, guide);
        FloatArray m(env, mask);
        if (!g.ok) return kLockFailed;
        auto img = g.image();
        if (m.data == nullptr || m.size != img.w * img.h || radius < 1) return kBadArgs;
        aicore::refineMask(img, m.data, radius, eps);
        return kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_ai_NativeAi_lensBlur(
    JNIEnv* env, jclass, jobject src, jfloatArray sharpness, jfloat radius, jobject out) {
    return guarded([&] {
        Locked s(env, src);
        Locked o(env, out);
        FloatArray a(env, sharpness);
        if (!s.ok || !o.ok) return kLockFailed;
        auto si = s.image();
        auto oi = o.image();
        if (a.data == nullptr || a.size != si.w * si.h || oi.w != si.w || oi.h != si.h || radius < 0) return kBadArgs;
        aicore::lensBlur(si, a.data, radius, oi);
        return kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_ai_NativeAi_composite(
    JNIEnv* env, jclass, jobject full, jobject blurred, jfloatArray sharpness) {
    return guarded([&] {
        Locked f(env, full);
        Locked b(env, blurred);
        FloatArray a(env, sharpness);
        if (!f.ok || !b.ok) return kLockFailed;
        auto bi = b.image();
        if (a.data == nullptr || a.size != bi.w * bi.h) return kBadArgs;
        aicore::composite(f.image(), bi, a.data);
        return kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_ai_NativeAi_smoothSkin(
    JNIEnv* env, jclass, jobject full, jfloatArray mask, jint mw, jint mh, jfloat strength) {
    return guarded([&] {
        Locked f(env, full);
        FloatArray m(env, mask);
        if (!f.ok) return kLockFailed;
        if (m.data == nullptr || m.size != mw * mh || mw < 2 || mh < 2) return kBadArgs;
        aicore::smoothSkin(f.image(), m.data, mw, mh, std::clamp(strength, 0.f, 1.f));
        return kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_ai_NativeAi_denoise(
    JNIEnv* env, jclass, jobject full, jfloat strength) {
    return guarded([&] {
        Locked f(env, full);
        if (!f.ok) return kLockFailed;
        aicore::denoise(f.image(), std::clamp(strength, 0.f, 1.f));
        return kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_ai_NativeAi_sharpen(
    JNIEnv* env, jclass, jobject full, jfloat amount) {
    return guarded([&] {
        Locked f(env, full);
        if (!f.ok) return kLockFailed;
        aicore::sharpen(f.image(), std::clamp(amount, 0.f, 3.f));
        return kOk;
    });
}

}  // extern "C"
