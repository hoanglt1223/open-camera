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

#define LOG_TAG "OpenCameraPipeline"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Burst {
    int width;
    int height;
    int capacity;
    std::vector<mf::Frame> frames;
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

// Returns [referenceIndex, noiseSigma, meanWeight, msAlign, msMerge, msFinish] or null on failure.
JNIEXPORT jdoubleArray JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeProcess(JNIEnv *env, jobject, jlong handle,
                                                          jint mode, jfloat toneEvStep,
                                                          jint reference, jbyteArray out,
                                                          jobject listener) {
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

    const mf::Frame &img = result.image;
    env->SetByteArrayRegion(out, 0, jsize(img.y.size()), reinterpret_cast<const jbyte *>(img.y.data()));
    env->SetByteArrayRegion(out, jsize(img.y.size()), jsize(img.vu.size()),
                            reinterpret_cast<const jbyte *>(img.vu.data()));

    const jdouble stats[] = {double(result.referenceIndex), result.noiseSigma, result.meanWeight,
                             result.msAlign, result.msMerge, result.msFinish};
    jdoubleArray arr = env->NewDoubleArray(6);
    if (arr) env->SetDoubleArrayRegion(arr, 0, 6, stats);
    return arr;
}

JNIEXPORT void JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeReleaseBurst(JNIEnv *, jobject, jlong handle) {
    delete fromHandle(handle);
}

}  // extern "C"
