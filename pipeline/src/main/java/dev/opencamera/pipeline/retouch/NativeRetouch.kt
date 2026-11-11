/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.util.Log

/**
 * JNI bindings for `libopencamera_retouch`. Calls return 0 (or a count) on success and a
 * negative code otherwise; callers check [available] first. Bitmaps must be ARGB_8888.
 * Soft masks are `mw x mh` floats stretched over `rect = [x, y, width, height]` in pixels.
 */
object NativeRetouch {
    const val OK = 0
    const val TIMEOUT = -4
    const val NO_SOURCE = -5

    val available: Boolean = try {
        System.loadLibrary("opencamera_retouch")
        true
    } catch (e: Throwable) {
        Log.e("NativeRetouch", "libopencamera_retouch unavailable", e)
        false
    }

    @JvmStatic external fun patchMatch(bitmap: Bitmap, mask: ByteArray, patchRadius: Int, budgetMs: Double): Int
    @JvmStatic external fun healSeams(bitmap: Bitmap, original: Bitmap, mask: ByteArray): Int
    @JvmStatic external fun smoothSkin(bitmap: Bitmap, mask: FloatArray, mw: Int, mh: Int, rect: FloatArray, strength: Float, radius: Int): Int
    @JvmStatic external fun redEye(bitmap: Bitmap, cx: Float, cy: Float, radius: Float): Int
    @JvmStatic external fun whitenTeeth(bitmap: Bitmap, mask: FloatArray, mw: Int, mh: Int, rect: FloatArray, strength: Float): Int
    @JvmStatic external fun brightenEyes(bitmap: Bitmap, mask: FloatArray, mw: Int, mh: Int, rect: FloatArray, strength: Float): Int
    @JvmStatic external fun warpGrid(src: Bitmap, dst: Bitmap, dx: FloatArray, dy: FloatArray, gw: Int, gh: Int): Int
}
