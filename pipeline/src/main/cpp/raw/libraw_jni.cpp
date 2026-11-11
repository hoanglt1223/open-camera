// SPDX-FileCopyrightText: Open Camera contributors
// SPDX-License-Identifier: Apache-2.0
//
// JNI glue for camera-maker RAW files. Uses only LibRaw's C API from the separately built,
// dynamically linked libopencamera_libraw.so (LGPL-2.1), so that library can be replaced.
// Every failure is returned as an error code; nothing here aborts.

#include <jni.h>

#include <cstring>

#include "libraw/libraw.h"

namespace {

void setInfo(JNIEnv *env, jintArray info, int code, int w, int h, int flip) {
    if (info == nullptr || env->GetArrayLength(info) < 4) return;
    jint v[4] = {code, w, h, flip};
    env->SetIntArrayRegion(info, 0, 4, v);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_opencamera_pipeline_raw_LibRawDecoder_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(libraw_version());
}

// Decodes [path] to 16-bit linear sRGB (camera white balance, no auto brightness).
// info receives {error, width, height, flip}; returns null on error.
extern "C" JNIEXPORT jshortArray JNICALL
Java_dev_opencamera_pipeline_raw_LibRawDecoder_nativeDecode(JNIEnv *env, jobject, jstring jpath, jboolean half,
                                                            jintArray info, jobjectArray camera) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return nullptr;
    libraw_data_t *raw = libraw_init(0);
    if (raw == nullptr) {
        env->ReleaseStringUTFChars(jpath, path);
        setInfo(env, info, -100, 0, 0, 0);
        return nullptr;
    }
    int rc = libraw_open_file(raw, path);
    env->ReleaseStringUTFChars(jpath, path);
    jshortArray result = nullptr;
    if (rc == LIBRAW_SUCCESS) {
        if (camera != nullptr && env->GetArrayLength(camera) >= 2) {
            jstring make = env->NewStringUTF(raw->idata.make);
            jstring model = env->NewStringUTF(raw->idata.model);
            env->SetObjectArrayElement(camera, 0, make);
            env->SetObjectArrayElement(camera, 1, model);
        }
        raw->params.output_color = 1;  // sRGB primaries
        raw->params.output_bps = 16;
        raw->params.gamm[0] = 1.0;     // linear
        raw->params.gamm[1] = 1.0;
        raw->params.no_auto_bright = 1;
        raw->params.use_camera_wb = 1;
        raw->params.use_camera_matrix = 1;
        raw->params.highlight = 0;
        raw->params.half_size = half ? 1 : 0;
        raw->params.user_qual = half ? 0 : 3;
        rc = libraw_unpack(raw);
        if (rc == LIBRAW_SUCCESS) rc = libraw_dcraw_process(raw);
        if (rc == LIBRAW_SUCCESS) {
            int err = 0;
            libraw_processed_image_t *img = libraw_dcraw_make_mem_image(raw, &err);
            if (img != nullptr && img->type == LIBRAW_IMAGE_BITMAP && img->colors == 3 && img->bits == 16) {
                const jsize n = static_cast<jsize>(img->width) * img->height * 3;
                result = env->NewShortArray(n);
                if (result != nullptr) {
                    env->SetShortArrayRegion(result, 0, n, reinterpret_cast<const jshort *>(img->data));
                    setInfo(env, info, 0, img->width, img->height, raw->sizes.flip);
                } else {
                    env->ExceptionClear();
                    setInfo(env, info, LIBRAW_UNSUFFICIENT_MEMORY, 0, 0, 0);
                }
            } else {
                setInfo(env, info, err != 0 ? err : -101, 0, 0, 0);
            }
            if (img != nullptr) libraw_dcraw_clear_mem(img);
        } else {
            setInfo(env, info, rc, 0, 0, 0);
        }
    } else {
        setInfo(env, info, rc, 0, 0, 0);
    }
    libraw_close(raw);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_opencamera_pipeline_raw_LibRawDecoder_nativeErrorText(JNIEnv *env, jobject, jint code) {
    return env->NewStringUTF(libraw_strerror(code));
}
