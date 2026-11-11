/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.graphics.Bitmap
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlRuntime
import kotlin.math.abs
import kotlin.math.ln

/**
 * Aesthetic score for picking the best frame of a burst. Uses the NIMA (MobileNet) model when
 * downloaded; otherwise a classical score from sharpness and exposure. Scores of one kind are
 * comparable with each other, so a burst is always ranked with a single [Score.byModel] kind.
 */
class AestheticScorer(private val runtime: MlRuntime) {
    data class Score(val value: Float, val byModel: Boolean, val sharpness: Float)

    val modelAvailable get() = runtime.isAvailable(MODEL)

    fun score(image: Bitmap, useModel: Boolean = modelAvailable): Score {
        val small = ImageTensors.scaled(image, SIZE, SIZE)
        val px = ImageTensors.pixels(small)
        if (small !== image) small.recycle()
        val sharp = Classical.sharpness(px, SIZE, SIZE)
        if (useModel) {
            val t = ImageTensors.argbToNhwc(px, SIZE, SIZE, mul = 2f, add = -1f)
            runtime.runOrNull(MODEL, listOf(t))?.first()?.data?.let { p ->
                return Score(nimaMean(p), true, sharp)
            }
        }
        return Score(Classical.quality(px, SIZE, SIZE), false, sharp)
    }

    /** Index of the best frame; ties and blur favour the sharper frame. */
    fun best(frames: List<Bitmap>): Int {
        if (frames.size <= 1) return 0
        val useModel = modelAvailable
        val scores = frames.map { score(it, useModel) }
        val anyModel = scores.any { it.byModel }
        val values = scores.map { if (anyModel && !it.byModel) Float.NEGATIVE_INFINITY else it.value }
        val maxSharp = scores.maxOf { it.sharpness }.coerceAtLeast(1e-6f)
        // A visibly blurred frame never wins on aesthetics alone.
        return values.indices.maxByOrNull { i ->
            values[i] + if (scores[i].sharpness < 0.6f * maxSharp) -100f else 0f
        } ?: 0
    }

    companion object {
        const val MODEL = "nima_mobilenet"
        private const val SIZE = 224

        /** Mean of the 1..10 score distribution. */
        fun nimaMean(p: FloatArray): Float {
            var s = 0f
            var total = 0f
            for (i in p.indices) {
                s += (i + 1) * p[i]
                total += p[i]
            }
            return if (total > 0f) s / total else 0f
        }
    }
}

/** Model-free image measures shared by the fallbacks. */
object Classical {
    fun luma(c: Int) = ((c shr 16 and 0xff) * 77 + (c shr 8 and 0xff) * 150 + (c and 0xff) * 29) shr 8

    /** Variance of the 4-neighbour Laplacian on luma, normalized to roughly [0, 1]. */
    fun sharpness(px: IntArray, w: Int, h: Int): Float {
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * luma(px[i]) - luma(px[i - 1]) - luma(px[i + 1]) - luma(px[i - w]) - luma(px[i + w])
            sum += l
            sq += l.toDouble() * l
            n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        val variance = sq / n - mean * mean
        return (ln(1.0 + variance) / ln(1.0 + 4000.0)).toFloat().coerceIn(0f, 1f)
    }

    /** 1..10 scale like the model: sharpness plus well-exposed, non-clipped histogram. */
    fun quality(px: IntArray, w: Int, h: Int): Float {
        var mean = 0.0
        var clipped = 0
        for (c in px) {
            val l = luma(c)
            mean += l
            if (l < 4 || l > 251) clipped++
        }
        mean /= px.size.coerceAtLeast(1)
        val exposure = 1f - (abs(mean - 118.0) / 118.0).toFloat().coerceIn(0f, 1f)
        val clip = 1f - (clipped.toFloat() / px.size.coerceAtLeast(1) * 4f).coerceIn(0f, 1f)
        return 1f + 9f * (0.55f * sharpness(px, w, h) + 0.3f * exposure + 0.15f * clip)
    }

    /**
     * Centre-surround colour contrast: each pixel's distance from a heavily blurred copy, a
     * cheap stand-in for a saliency model. Returns a map in [0, 1].
     */
    fun saliency(px: IntArray, w: Int, h: Int): Mask {
        val r = FloatArray(w * h) { (px[it] shr 16 and 0xff).toFloat() }
        val g = FloatArray(w * h) { (px[it] shr 8 and 0xff).toFloat() }
        val b = FloatArray(w * h) { (px[it] and 0xff).toFloat() }
        val radius = maxOf(w, h) / 6
        val br = boxBlur(boxBlur(r, w, h, radius), w, h, radius)
        val bg = boxBlur(boxBlur(g, w, h, radius), w, h, radius)
        val bb = boxBlur(boxBlur(b, w, h, radius), w, h, radius)
        val out = FloatArray(w * h)
        var max = 1e-6f
        for (i in out.indices) {
            val dr = r[i] - br[i]
            val dg = g[i] - bg[i]
            val db = b[i] - bb[i]
            out[i] = kotlin.math.sqrt(dr * dr + dg * dg + db * db)
            if (out[i] > max) max = out[i]
        }
        val smooth = boxBlur(out, w, h, 2)
        for (i in smooth.indices) smooth[i] = (smooth[i] / max).coerceIn(0f, 1f)
        return Mask(w, h, smooth)
    }

    /** Separable box blur with clamped edges. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val k = 1f / (2 * radius + 1)
        for (y in 0 until h) {
            var acc = 0f
            for (x in -radius..radius) acc += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc * k
                acc += src[y * w + (x + radius + 1).coerceAtMost(w - 1)] - src[y * w + (x - radius).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -radius..radius) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc * k
                acc += tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - radius).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }
}
