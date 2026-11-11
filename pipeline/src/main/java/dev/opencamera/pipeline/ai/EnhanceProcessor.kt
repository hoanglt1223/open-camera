/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import android.graphics.Bitmap
import android.util.Log
import dev.opencamera.ml.MlException
import dev.opencamera.ml.tasks.ImageRestorer

/**
 * Denoise and super-resolution for the editor and background jobs. The model path (NAFNet,
 * Real-ESRGAN) runs within a time budget; when the model is missing, too slow or out of
 * memory, the classical path (guided-filter denoise, bilinear + unsharp mask) is used and
 * [Result.fallbackReason] says why.
 */
class EnhanceProcessor(private val restorer: ImageRestorer) {
    enum class Method { MODEL, CLASSICAL }

    class Result(val bitmap: Bitmap, val method: Method, val fallbackReason: MlException.Reason?)

    /** Returns a new bitmap or [src] itself (modified in place) on the classical path. */
    fun denoise(src: Bitmap, strength: Float, budgetMs: Long, useModel: Boolean = true): Result {
        var reason: MlException.Reason? = if (useModel) null else MlException.Reason.DISABLED
        if (useModel) {
            try {
                val out = restorer.denoise(src, budgetMs)
                if (strength < 0.999f) blend(src, out, strength)
                return Result(out, Method.MODEL, null)
            } catch (e: MlException) {
                Log.w(TAG, "Denoise model unavailable (${e.reason}), classical path")
                reason = e.reason
            }
        }
        check(NativeAi.available && NativeAi.denoise(src, strength) == 0) { "Classical denoise failed" }
        return Result(src, Method.CLASSICAL, reason)
    }

    /** Upscales by [factor] (2 or 4). Output is capped at [MAX_OUTPUT_PIXELS]. */
    fun upscale(src: Bitmap, factor: Int, budgetMs: Long, useModel: Boolean = true): Result {
        require(factor == 2 || factor == 4) { "Factor must be 2 or 4" }
        require(src.width.toLong() * src.height * factor * factor <= MAX_OUTPUT_PIXELS) {
            "Output would exceed ${MAX_OUTPUT_PIXELS / 1_000_000} MP"
        }
        var reason: MlException.Reason? = if (useModel) null else MlException.Reason.DISABLED
        if (useModel) {
            try {
                val x4 = restorer.upscale4x(src, budgetMs, outScale = factor)
                return Result(x4, Method.MODEL, null)
            } catch (e: MlException) {
                Log.w(TAG, "Super-resolution model unavailable (${e.reason}), classical path")
                reason = e.reason
            }
        }
        val out = Bitmap.createScaledBitmap(src, src.width * factor, src.height * factor, true)
        if (NativeAi.available) NativeAi.sharpen(out, 0.6f)
        return Result(out, Method.CLASSICAL, reason)
    }

    private fun blend(original: Bitmap, result: Bitmap, strength: Float) {
        val w = original.width
        val a = IntArray(w)
        val b = IntArray(w)
        for (y in 0 until original.height) {
            original.getPixels(a, 0, w, 0, y, w, 1)
            result.getPixels(b, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = a[x]
                val q = b[x]
                fun ch(s: Int) = (((p shr s) and 0xff) * (1 - strength) + ((q shr s) and 0xff) * strength).toInt()
                b[x] = (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
            result.setPixels(b, 0, w, 0, y, w, 1)
        }
    }

    companion object {
        private const val TAG = "EnhanceProcessor"
        const val MAX_OUTPUT_PIXELS = 50_000_000L
    }
}
