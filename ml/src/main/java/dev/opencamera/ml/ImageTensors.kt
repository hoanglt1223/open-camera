/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/** Conversions between ARGB pixels and model tensors. RGB values map to [0, 1] unless stated. */
object ImageTensors {
    /** Scales [src] (or its [crop]) to exactly [w]x[h] with bilinear filtering. */
    fun scaled(src: Bitmap, w: Int, h: Int, crop: Rect? = null): Bitmap {
        if (crop == null && src.width == w && src.height == h && src.config == Bitmap.Config.ARGB_8888) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, crop, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    /** NHWC [1, h, w, 3]; value = channel / 255 * [mul] + [add]. */
    fun argbToNhwc(px: IntArray, w: Int, h: Int, mul: Float = 1f, add: Float = 0f): Tensor {
        val t = Tensor(intArrayOf(1, h, w, 3))
        val d = t.data
        val k = mul / 255f
        for (i in 0 until w * h) {
            val c = px[i]
            d[i * 3] = (c shr 16 and 0xff) * k + add
            d[i * 3 + 1] = (c shr 8 and 0xff) * k + add
            d[i * 3 + 2] = (c and 0xff) * k + add
        }
        return t
    }

    /** NCHW [1, 3, h, w] in [0, 1]. */
    fun argbToNchw(px: IntArray, w: Int, h: Int): Tensor {
        val t = Tensor(intArrayOf(1, 3, h, w))
        val d = t.data
        val plane = w * h
        for (i in 0 until plane) {
            val c = px[i]
            d[i] = (c shr 16 and 0xff) / 255f
            d[plane + i] = (c shr 8 and 0xff) / 255f
            d[2 * plane + i] = (c and 0xff) / 255f
        }
        return t
    }

    /** Inverse of [argbToNchw]; values are clamped to [0, 1]. */
    fun nchwToArgb(t: Tensor, w: Int, h: Int): IntArray {
        val plane = w * h
        require(t.size >= 3 * plane) { "Tensor $t too small for ${w}x$h" }
        val d = t.data
        return IntArray(plane) { i ->
            val r = (d[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val g = (d[plane + i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val b = (d[2 * plane + i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
