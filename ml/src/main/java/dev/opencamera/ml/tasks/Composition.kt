/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.graphics.Bitmap
import android.os.SystemClock
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.Tensor
import kotlin.math.abs
import kotlin.math.hypot

/** Normalized rectangle, all values in [0, 1]. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Rule-of-thirds assist. The subject is the saliency centroid (U²-Net-p when downloaded,
 * centre-surround contrast otherwise); the hint points at the nearest thirds intersection and
 * proposes the largest same-aspect crop that puts the subject there.
 */
object Composition {
    data class Suggestion(
        val subjectX: Float,
        val subjectY: Float,
        val targetX: Float,
        val targetY: Float,
        val crop: NormRect?,
        val confidence: Float,
    ) {
        /** Already well placed: no hint needed. */
        val aligned get() = hypot(subjectX - targetX, subjectY - targetY) < ALIGNED
    }

    const val ALIGNED = 0.04f
    private const val MIN_CROP = 0.6f

    fun suggest(mask: Mask): Suggestion? {
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            val v = mask.data[y * mask.width + x]
            if (v < 0.3f) continue
            val wgt = (v * v).toDouble()
            sx += wgt * (x + 0.5) / mask.width
            sy += wgt * (y + 0.5) / mask.height
            sw += wgt
        }
        val coverage = sw / (mask.width * mask.height)
        // Nothing stands out, or the "subject" fills the frame: no meaningful hint.
        if (sw <= 0 || coverage < 0.005 || coverage > 0.6) return null
        val cx = (sx / sw).toFloat()
        val cy = (sy / sw).toFloat()
        val thirds = listOf(1f / 3, 2f / 3)
        val tx = thirds.minBy { abs(it - cx) }
        val ty = thirds.minBy { abs(it - cy) }
        return Suggestion(cx, cy, tx, ty, crop(cx, cy, tx, ty), (coverage * 20).toFloat().coerceIn(0.2f, 1f))
    }

    /**
     * Largest crop of scale s (same aspect) with the subject at (tx, ty) of the crop:
     * left = cx - tx * s must lie in [0, 1 - s], likewise for top.
     */
    fun crop(cx: Float, cy: Float, tx: Float, ty: Float): NormRect? {
        var s = 1f
        while (s >= MIN_CROP) {
            val l = cx - tx * s
            val t = cy - ty * s
            if (l >= -1e-4f && l + s <= 1f + 1e-4f && t >= -1e-4f && t + s <= 1f + 1e-4f) {
                return if (s > 0.98f) null else NormRect(l.coerceAtLeast(0f), t.coerceAtLeast(0f), (l + s).coerceAtMost(1f), (t + s).coerceAtMost(1f))
            }
            s -= 0.02f
        }
        return null
    }
}

class SaliencyDetector(private val runtime: MlRuntime, private val maxModelMs: Long = Long.MAX_VALUE) {
    @Volatile
    private var modelTooSlow = false

    fun saliency(frame: Bitmap): Pair<Mask, Boolean> {
        if (!modelTooSlow && runtime.isAvailable(MODEL)) {
            val t0 = SystemClock.elapsedRealtime()
            val small = ImageTensors.scaled(frame, SIZE, SIZE)
            val px = ImageTensors.pixels(small)
            if (small !== frame) small.recycle()
            val t = normalized(px)
            val result = runtime.runOrNull(MODEL, listOf(t), TIMEOUT_MS)
            // Live use: a model slower than the budget would keep the CPU busy; switch to the classical map.
            if (SystemClock.elapsedRealtime() - t0 > maxModelMs) modelTooSlow = true
            result?.first()?.data?.let { d ->
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (i in 0 until SIZE * SIZE) {
                    lo = minOf(lo, d[i])
                    hi = maxOf(hi, d[i])
                }
                val k = if (hi > lo) 1f / (hi - lo) else 0f
                return Mask(SIZE, SIZE, FloatArray(SIZE * SIZE) { (d[it] - lo) * k }) to true
            }
        }
        val small = ImageTensors.scaled(frame, FALLBACK_W, FALLBACK_H)
        val px = ImageTensors.pixels(small)
        if (small !== frame) small.recycle()
        return Classical.saliency(px, FALLBACK_W, FALLBACK_H) to false
    }

    /** ImageNet mean/std normalization, NCHW. */
    private fun normalized(px: IntArray): Tensor {
        val t = Tensor(intArrayOf(1, 3, SIZE, SIZE))
        val plane = SIZE * SIZE
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until plane) {
            val c = px[i]
            t.data[i] = ((c shr 16 and 0xff) / 255f - mean[0]) / std[0]
            t.data[plane + i] = ((c shr 8 and 0xff) / 255f - mean[1]) / std[1]
            t.data[2 * plane + i] = ((c and 0xff) / 255f - mean[2]) / std[2]
        }
        return t
    }

    companion object {
        const val MODEL = "u2netp"
        private const val SIZE = 320
        private const val FALLBACK_W = 64
        private const val FALLBACK_H = 48
        private const val TIMEOUT_MS = 1500L
    }
}
