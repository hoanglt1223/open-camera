// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// JNI entry points of libopencamera_retouch. Every call returns 0 (or a count) on success and
// a negative code on failure; nothing aborts the process.

#include <android/bitmap.h>
#include <jni.h>

#include <new>
#include <vector>

#include "retouch_core.h"

namespace {

constexpr int kLockFailed = -2;

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
    retouch::Image image() const {
        return {pixels, static_cast<int>(info.width), static_cast<int>(info.height), static_cast<int>(info.stride)};
    }
};

template <class F>
int guarded(F f) {
    try {
        return f();
    } catch (const std::bad_alloc&) {
        return retouch::kNoMemory;
    } catch (...) {
        return retouch::kBadArgs;
    }
}

// Copies the soft mask out of the Java array so no critical section spans the long work.
bool maskView(JNIEnv* env, jfloatArray mask, jint mw, jint mh, jfloatArray rect, std::vector<float>& store,
              retouch::MaskView& view) {
    if (mask == nullptr || rect == nullptr || mw <= 0 || mh <= 0) return false;
    if (env->GetArrayLength(mask) != mw * mh || env->GetArrayLength(rect) != 4) return false;
    store.resize(size_t(mw) * mh);
    env->GetFloatArrayRegion(mask, 0, mw * mh, store.data());
    float r[4];
    env->GetFloatArrayRegion(rect, 0, 4, r);
    view.m = store.data();
    view.mw = mw;
    view.mh = mh;
    view.x = r[0];
    view.y = r[1];
    view.w = r[2];
    view.h = r[3];
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_retouch_NativeRetouch_patchMatch(
    JNIEnv* env, jclass, jobject bitmap, jbyteArray mask, jint patchRadius, jdouble budgetMs) {
    return guarded([&] {
        Locked b(env, bitmap);
        if (!b.ok) return kLockFailed;
        auto img = b.image();
        if (mask == nullptr || env->GetArrayLength(mask) != img.w * img.h) return retouch::kBadArgs;
        std::vector<uint8_t> m(size_t(img.w) * img.h);
        env->GetByteArrayRegion(mask, 0, img.w * img.h, reinterpret_cast<jbyte*>(m.data()));
        return retouch::patchMatchInpaint(img, m.data(), patchRadius, budgetMs);
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_retouch_NativeRetouch_healSeams(
    JNIEnv* env, jclass, jobject bitmap, jobject original, jbyteArray mask) {
    return guarded([&] {
        Locked b(env, bitmap);
        Locked o(env, original);
        if (!b.ok || !o.ok) return kLockFailed;
        auto img = b.image();
        auto orig = o.image();
        if (orig.w != img.w || orig.h != img.h) return retouch::kBadArgs;
        if (mask == nullptr || env->GetArrayLength(mask) != img.w * img.h) return retouch::kBadArgs;
        std::vector<uint8_t> m(size_t(img.w) * img.h);
        env->GetByteArrayRegion(mask, 0, img.w * img.h, reinterpret_cast<jbyte*>(m.data()));
        retouch::healSeams(img, orig, m.data());
        return retouch::kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_retouch_NativeRetouch_smoothSkin(
    JNIEnv* env, jclass, jobject bitmap, jfloatArray mask, jint mw, jint mh, jfloatArray rect, jfloat strength,
    jint radius) {
    return guarded([&] {
        std::vector<float> store;
        retouch::MaskView view;
        if (!maskView(env, mask, mw, mh, rect, store, view)) return retouch::kBadArgs;
        Locked b(env, bitmap);
        if (!b.ok) return kLockFailed;
        retouch::smoothSkin(b.image(), view, strength, radius);
        return retouch::kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_retouch_NativeRetouch_redEye(
    JNIEnv* env, jclass, jobject bitmap, jfloat cx, jfloat cy, jfloat radius) {
    return guarded([&] {
        Locked b(env, bitmap);
        if (!b.ok) return kLockFailed;
        return retouch::redEye(b.image(), cx, cy, radius);
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_retouch_NativeRetouch_whitenTeeth(
    JNIEnv* env, jclass, jobject bitmap, jfloatArray mask, jint mw, jint mh, jfloatArray rect, jfloat strength) {
    return guarded([&] {
        std::vector<float> store;
        retouch::MaskView view;
        if (!maskView(env, mask, mw, mh, rect, store, view)) return retouch::kBadArgs;
        Locked b(env, bitmap);
        if (!b.ok) return kLockFailed;
        retouch::whitenTeeth(b.image(), view, strength);
        return retouch::kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_retouch_NativeRetouch_brightenEyes(
    JNIEnv* env, jclass, jobject bitmap, jfloatArray mask, jint mw, jint mh, jfloatArray rect, jfloat strength) {
    return guarded([&] {
        std::vector<float> store;
        retouch::MaskView view;
        if (!maskView(env, mask, mw, mh, rect, store, view)) return retouch::kBadArgs;
        Locked b(env, bitmap);
        if (!b.ok) return kLockFailed;
        retouch::brightenEyes(b.image(), view, strength);
        return retouch::kOk;
    });
}

JNIEXPORT jint JNICALL Java_dev_opencamera_pipeline_retouch_NativeRetouch_warpGrid(
    JNIEnv* env, jclass, jobject src, jobject dst, jfloatArray dx, jfloatArray dy, jint gw, jint gh) {
    return guarded([&] {
        if (dx == nullptr || dy == nullptr || gw < 2 || gh < 2) return retouch::kBadArgs;
        if (env->GetArrayLength(dx) != gw * gh || env->GetArrayLength(dy) != gw * gh) return retouch::kBadArgs;
        std::vector<float> fx(size_t(gw) * gh), fy(size_t(gw) * gh);
        env->GetFloatArrayRegion(dx, 0, gw * gh, fx.data());
        env->GetFloatArrayRegion(dy, 0, gw * gh, fy.data());
        Locked s(env, src);
        Locked d(env, dst);
        if (!s.ok || !d.ok) return kLockFailed;
        auto si = s.image();
        auto di = d.image();
        if (si.w != di.w || si.h != di.h) return retouch::kBadArgs;
        retouch::warpGrid(si, di, fx.data(), fy.data(), gw, gh);
        return retouch::kOk;
    });
}

}  // extern "C"
