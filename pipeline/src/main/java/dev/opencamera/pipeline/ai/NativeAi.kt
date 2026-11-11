/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import android.graphics.Bitmap
import android.util.Log

/**
 * JNI bindings for `libopencamera_ai`. Every call returns 0 on success or a negative error code;
 * callers check [available] first and treat a non-zero code as "feature unavailable".
 * Bitmaps must be ARGB_8888.
 */
object NativeAi {
    val available: Boolean = try {
        System.loadLibrary("opencamera_ai")
        true
    } catch (e: Throwable) {
        Log.e("NativeAi", "libopencamera_ai unavailable", e)
        false
    }

    @JvmStatic external fun refineMask(guide: Bitmap, mask: FloatArray, radius: Int, eps: Float): Int
    @JvmStatic external fun lensBlur(src: Bitmap, sharpness: FloatArray, radius: Float, out: Bitmap): Int
    @JvmStatic external fun composite(full: Bitmap, blurred: Bitmap, sharpness: FloatArray): Int
    @JvmStatic external fun smoothSkin(full: Bitmap, mask: FloatArray, mw: Int, mh: Int, strength: Float): Int
    @JvmStatic external fun denoise(full: Bitmap, strength: Float): Int
    @JvmStatic external fun sharpen(full: Bitmap, amount: Float): Int
}
