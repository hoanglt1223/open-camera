/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_opencamera_pipeline_NativePipeline_nativeVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF("opencamera-pipeline 0.1");
}