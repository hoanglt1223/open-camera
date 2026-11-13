/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <android/log.h>
#include <jni.h>

#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "mf/pipeline.h"
#include "mf/raw.h"
#include "stack/stack.h"

#define LOG_TAG "OpenCameraPipeline"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Burst {
    int width;
    int height;
    int capacity;
    std::vector<mf::Frame> frames;
    mf::GrayF toneBase;  // of the last merge
};

Burst *fromHandle(jlong handle) { return reinterpret_cast<Burst *>(handle); }

uint8_t *bufferAddress(JNIEnv *env, jobject buffer, jlong *capacity) {
    if (!buffer) return nullptr;
    *capacity = env->GetDirectBufferCapacity(buffer);
    return static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("opencamera-pipeline 0.2");
}

JNIEXPORT jlong JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeCreateBurst(JNIEnv *, jobject, jint width,
                                                              jint height, jint capacity) {
    if (width < 64 || height < 64 || capacity < 1) return 0;
    auto *burst = new (std::nothrow) Burst{width & ~1, height & ~1, capacity, {}};
    if (burst) burst->frames.reserve(capacity);
    return reinterpret_cast<jlong>(burst);
}

// Copies a YUV_420_888 image (any row/pixel stride, I420/NV12/NV21) into NV21 storage.
JNIEXPORT jboolean JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeAddFrame(JNIEnv *env, jobject, jlong handle,
                                                           jobject yBuf, jint yRowStride,
                                                           jobject uBuf, jobject vBuf,
                                                           jint uvRowStride, jint uvPixelStride) {
    Burst *b = fromHandle(handle);
    if (!b || int(b->frames.size()) >= b->capacity) return JNI_FALSE;
    jlong yCap = 0, uCap = 0, vCap = 0;
    const uint8_t *y = bufferAddress(env, yBuf, &yCap);
    const uint8_t *u = bufferAddress(env, uBuf, &uCap);
    const uint8_t *v = bufferAddress(env, vBuf, &vCap);
    if (!y || !u || !v) return JNI_FALSE;
    const int w = b->width, h = b->height;
    const jlong yNeed = jlong(yRowStride) * (h - 1) + w;
    const jlong uvNeed = jlong(uvRowStride) * (h / 2 - 1) + jlong(uvPixelStride) * (w / 2 - 1) + 1;
    if (yRowStride < w || yCap < yNeed || uCap < uvNeed || vCap < uvNeed) {
        LOGE("frame buffers too small for %dx%d", w, h);
        return JNI_FALSE;
    }
    try {
        mf::Frame f(w, h);
        for (int r = 0; r < h; ++r) std::memcpy(&f.y[size_t(r) * w], y + size_t(r) * yRowStride, w);
        for (int r = 0; r < h / 2; ++r) {
            uint8_t *dst = &f.vu[size_t(r) * w];
            const uint8_t *vr = v + size_t(r) * uvRowStride;
            const uint8_t *ur = u + size_t(r) * uvRowStride;
            for (int c = 0; c < w / 2; ++c) {
                dst[2 * c] = vr[c * uvPixelStride];
                dst[2 * c + 1] = ur[c * uvPixelStride];
            }
        }
        b->frames.push_back(std::move(f));
    } catch (const std::bad_alloc &) {
        LOGE("out of memory while adding frame %zu", b->frames.size());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeFrameCount(JNIEnv *, jobject, jlong handle) {
    Burst *b = fromHandle(handle);
    return b ? jint(b->frames.size()) : 0;
}

JNIEXPORT jboolean JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeExportFrame(JNIEnv *env, jobject, jlong handle,
                                                              jint index, jbyteArray out) {
    Burst *b = fromHandle(handle);
    if (!b || index < 0 || index >= int(b->frames.size())) return JNI_FALSE;
    const mf::Frame &f = b->frames[index];
    if (env->GetArrayLength(out) < jsize(f.y.size() + f.vu.size())) return JNI_FALSE;
    env->SetByteArrayRegion(out, 0, jsize(f.y.size()), reinterpret_cast<const jbyte *>(f.y.data()));
    env->SetByteArrayRegion(out, jsize(f.y.size()), jsize(f.vu.size()),
                            reinterpret_cast<const jbyte *>(f.vu.data()));
    return JNI_TRUE;
}

// Pre tone mapping luma of the last merge as [width, height, px...], or null if there is none.
JNIEXPORT jfloatArray JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeExportToneBase(JNIEnv *env, jobject, jlong handle) {
    Burst *b = fromHandle(handle);
    if (!b || b->toneBase.px.empty()) return nullptr;
    const mf::GrayF &t = b->toneBase;
    const jsize n = jsize(t.px.size());
    jfloatArray arr = env->NewFloatArray(n + 2);
    if (!arr) return nullptr;
    const jfloat header[] = {jfloat(t.width), jfloat(t.height)};
    env->SetFloatArrayRegion(arr, 0, 2, header);
    env->SetFloatArrayRegion(arr, 2, n, t.px.data());
    return arr;
}

// Adds an NV21 frame of the burst size (frames restored from disk).
JNIEXPORT jboolean JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeAddNv21(JNIEnv *env, jobject, jlong handle,
                                                          jbyteArray nv21) {
    Burst *b = fromHandle(handle);
    if (!b || !nv21 || int(b->frames.size()) >= b->capacity) return JNI_FALSE;
    const size_t ySize = size_t(b->width) * b->height;
    if (size_t(env->GetArrayLength(nv21)) < ySize * 3 / 2) return JNI_FALSE;
    try {
        mf::Frame f(b->width, b->height);
        env->GetByteArrayRegion(nv21, 0, jsize(ySize), reinterpret_cast<jbyte *>(f.y.data()));
        env->GetByteArrayRegion(nv21, jsize(ySize), jsize(f.vu.size()), reinterpret_cast<jbyte *>(f.vu.data()));
        b->frames.push_back(std::move(f));
    } catch (const std::bad_alloc &) {
        LOGE("out of memory while restoring frame %zu", b->frames.size());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Develops a RAW16 frame into the burst. params = [cfa, black0..3, white, wbR, wbGe, wbGo, wbB,
// ccm0..8, gain] (20 values); lsc is optional (lscW x lscH x 4 gains).
JNIEXPORT jboolean JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeAddRawFrame(JNIEnv *env, jobject, jlong handle,
                                                              jobject rawBuf, jint rowStride,
                                                              jfloatArray params, jfloatArray lsc,
                                                              jint lscW, jint lscH) {
    Burst *b = fromHandle(handle);
    if (!b || int(b->frames.size()) >= b->capacity || !params || env->GetArrayLength(params) < 20) {
        return JNI_FALSE;
    }
    jlong cap = 0;
    const uint8_t *raw = bufferAddress(env, rawBuf, &cap);
    if (!raw || rowStride < b->width * 2 || cap < jlong(rowStride) * (b->height - 1) + b->width * 2) {
        LOGE("raw buffer too small for %dx%d", b->width, b->height);
        return JNI_FALSE;
    }
    jfloat p[20];
    env->GetFloatArrayRegion(params, 0, 20, p);
    mf::RawParams rp;
    rp.cfa = static_cast<mf::Cfa>(int(p[0]));
    for (int i = 0; i < 4; ++i) rp.black[i] = p[1 + i];
    rp.white = p[5];
    for (int i = 0; i < 4; ++i) rp.wb[i] = p[6 + i];
    for (int i = 0; i < 9; ++i) rp.ccm[i] = p[10 + i];
    rp.gain = p[19];
    std::vector<float> shading;
    if (lsc && lscW >= 2 && lscH >= 2 && env->GetArrayLength(lsc) >= lscW * lscH * 4) {
        shading.resize(size_t(lscW) * lscH * 4);
        env->GetFloatArrayRegion(lsc, 0, jsize(shading.size()), shading.data());
        rp.lsc = shading.data();
        rp.lscW = lscW;
        rp.lscH = lscH;
    }
    try {
        mf::Frame f;
        if (!mf::developRaw(reinterpret_cast<const uint16_t *>(raw), b->width, b->height, rowStride, rp, f)) {
            LOGE("raw development rejected the frame");
            return JNI_FALSE;
        }
        b->frames.push_back(std::move(f));
    } catch (const std::bad_alloc &) {
        LOGE("out of memory while developing raw frame %zu", b->frames.size());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Box-downsampled luma of one frame, (width / factor) x (height / factor).
JNIEXPORT jboolean JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeExportLuma(JNIEnv *env, jobject, jlong handle,
                                                             jint index, jint factor, jbyteArray out) {
    Burst *b = fromHandle(handle);
    if (!b || factor < 1 || index < 0 || index >= int(b->frames.size())) return JNI_FALSE;
    const mf::Frame &f = b->frames[index];
    const int w = f.width / factor, h = f.height / factor;
    if (w < 1 || h < 1 || env->GetArrayLength(out) < w * h) return JNI_FALSE;
    std::vector<uint8_t> small(size_t(w) * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint32_t sum = 0;
            for (int j = 0; j < factor; ++j) {
                const uint8_t *row = &f.y[size_t(y * factor + j) * f.width + size_t(x) * factor];
                for (int i = 0; i < factor; ++i) sum += row[i];
            }
            small[size_t(y) * w + x] = uint8_t(sum / uint32_t(factor * factor));
        }
    }
    env->SetByteArrayRegion(out, 0, jsize(small.size()), reinterpret_cast<const jbyte *>(small.data()));
    return JNI_TRUE;
}

// Returns [referenceIndex, noiseSigma, meanWeight, msAlign, msMerge, msFinish] or null on failure.
// crop = [x, y, w, h] in frame pixels or null; the output always has the burst frame size.
JNIEXPORT jdoubleArray JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeProcessCrop(JNIEnv *env, jobject, jlong handle,
                                                              jint mode, jfloat toneEvStep,
                                                              jint reference, jintArray crop,
                                                              jbyteArray out, jobject listener);

JNIEXPORT jdoubleArray JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeProcess(JNIEnv *env, jobject self, jlong handle,
                                                          jint mode, jfloat toneEvStep,
                                                          jint reference, jbyteArray out,
                                                          jobject listener) {
    return Java_dev_opencamera_pipeline_NativePipeline_nativeProcessCrop(
        env, self, handle, mode, toneEvStep, reference, nullptr, out, listener);
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeProcessCrop(JNIEnv *env, jobject, jlong handle,
                                                              jint mode, jfloat toneEvStep,
                                                              jint reference, jintArray crop,
                                                              jbyteArray out, jobject listener) {
    Burst *b = fromHandle(handle);
    if (!b || b->frames.empty()) return nullptr;
    const jsize need = jsize(size_t(b->width) * b->height * 3 / 2);
    if (env->GetArrayLength(out) < need) return nullptr;

    jmethodID onProgress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        onProgress = env->GetMethodID(cls, "onProgress", "(F)V");
        env->DeleteLocalRef(cls);
    }
    // Progress is reported only from the calling thread between stages.
    auto progress = [&](float p) {
        if (onProgress) env->CallVoidMethod(listener, onProgress, p);
    };

    mf::Params params;
    params.mode = static_cast<mf::Mode>(mode);
    params.toneEvStep = toneEvStep;
    params.reference = reference;
    if (crop && env->GetArrayLength(crop) >= 4) {
        jint c[4];
        env->GetIntArrayRegion(crop, 0, 4, c);
        params.cropX = c[0];
        params.cropY = c[1];
        params.cropW = c[2];
        params.cropH = c[3];
    }
    b->toneBase = mf::GrayF();
    mf::Result result;
    std::string error;
    if (!mf::processBurst(b->frames, params, result, progress, &error)) {
        LOGE("burst processing failed: %s", error.c_str());
        return nullptr;
    }
    if (env->ExceptionCheck()) return nullptr;
    LOGI("merged %zu frames %dx%d mode %d: align %.0f ms, merge %.0f ms, finish %.0f ms",
         b->frames.size(), b->width, b->height, mode, result.msAlign, result.msMerge,
         result.msFinish);

    b->toneBase = std::move(result.toneBase);
    const mf::Frame &img = result.image;
    if (img.width != b->width || img.height != b->height) {
        LOGE("unexpected output size %dx%d", img.width, img.height);
        return nullptr;
    }
    env->SetByteArrayRegion(out, 0, jsize(img.y.size()), reinterpret_cast<const jbyte *>(img.y.data()));
    env->SetByteArrayRegion(out, jsize(img.y.size()), jsize(img.vu.size()),
                            reinterpret_cast<const jbyte *>(img.vu.data()));

    const jdouble stats[] = {double(result.referenceIndex), result.noiseSigma, result.meanWeight,
                             result.msAlign, result.msMerge, result.msFinish};
    jdoubleArray arr = env->NewDoubleArray(6);
    if (arr) env->SetDoubleArrayRegion(arr, 0, 6, stats);
    return arr;
}

// Creative stacking. params = [keepFraction, rlIterations, rlSigma, sharpen, strength].
// Returns [referenceIndex, framesUsed, ms] or null on failure.
JNIEXPORT jdoubleArray JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeStack(JNIEnv *env, jobject, jlong handle,
                                                        jint op, jint reference, jboolean align,
                                                        jfloatArray params, jbyteArray out) {
    Burst *b = fromHandle(handle);
    if (!b || b->frames.empty() || op < 0 || op > 4) return nullptr;
    const jsize need = jsize(size_t(b->width) * b->height * 3 / 2);
    if (env->GetArrayLength(out) < need || !params || env->GetArrayLength(params) < 5) return nullptr;
    jfloat p[5];
    env->GetFloatArrayRegion(params, 0, 5, p);
    mf::StackParams sp;
    sp.op = static_cast<mf::StackOp>(op);
    sp.reference = reference;
    sp.align = align == JNI_TRUE;
    sp.keepFraction = p[0];
    sp.rlIterations = int(p[1]);
    sp.rlSigma = p[2];
    sp.sharpen = p[3];
    sp.strength = p[4];
    mf::StackResult result;
    std::string error;
    if (!mf::stackBurst(b->frames, sp, result, &error)) {
        LOGE("stack op %d failed: %s", op, error.c_str());
        return nullptr;
    }
    LOGI("stacked %d of %zu frames %dx%d op %d in %.0f ms", result.framesUsed, b->frames.size(),
         b->width, b->height, op, result.ms);
    const mf::Frame &img = result.image;
    env->SetByteArrayRegion(out, 0, jsize(img.y.size()), reinterpret_cast<const jbyte *>(img.y.data()));
    env->SetByteArrayRegion(out, jsize(img.y.size()), jsize(img.vu.size()),
                            reinterpret_cast<const jbyte *>(img.vu.data()));
    const jdouble stats[] = {double(result.referenceIndex), double(result.framesUsed), result.ms};
    jdoubleArray arr = env->NewDoubleArray(3);
    if (arr) env->SetDoubleArrayRegion(arr, 0, 3, stats);
    return arr;
}

// Frame indices from sharpest to softest, or null.
JNIEXPORT jintArray JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeRankSharpness(JNIEnv *env, jobject, jlong handle) {
    Burst *b = fromHandle(handle);
    if (!b || b->frames.empty()) return nullptr;
    std::vector<int> order;
    try {
        order = mf::rankBySharpness(b->frames);
    } catch (const std::exception &) {
        return nullptr;
    }
    jintArray arr = env->NewIntArray(jsize(order.size()));
    if (arr) env->SetIntArrayRegion(arr, 0, jsize(order.size()), reinterpret_cast<const jint *>(order.data()));
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeReleaseBurst(JNIEnv *, jobject, jlong handle) {
    delete fromHandle(handle);
}

}  // extern "C"
