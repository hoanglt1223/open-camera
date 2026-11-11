/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds a small bright disc on a dark background (the moon, or any bright round target) in a
 * luma plane. Bright-blob segmentation followed by a circle fit from the blob moments, then
 * checks of fill (disc is solid), compactness (nothing outside the circle) and contrast
 * against a surrounding ring.
 */
object MoonDetector {
    /** Centre and radius as fractions of the analysed frame width/height and min side. */
    data class Detection(val cx: Float, val cy: Float, val radius: Float, val contrast: Float)

    private const val WORK_SIZE = 160

    fun detect(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Detection? {
        if (width < 16 || height < 16 || luma.size < rowStride * (height - 1) + width) return null
        val step = max(1, max(width, height) / WORK_SIZE)
        val w = width / step
        val h = height / step
        val img = IntArray(w * h)
        var sum = 0L
        var maxV = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = luma[(y * step) * rowStride + x * step].toInt() and 0xff
            img[y * w + x] = v
            sum += v
            if (v > maxV) maxV = v
        }
        val mean = sum.toFloat() / (w * h)
        // Night sky: mostly dark frame with something much brighter in it.
        if (mean > 90f || maxV < 120 || maxV < mean * 2.5f) return null
        val threshold = max(mean * 2f, maxV * 0.5f).toInt()

        val label = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        var best: Blob? = null
        var next = 0
        for (start in img.indices) {
            if (img[start] < threshold || label[start] >= 0) continue
            val blob = Blob()
            var sp = 0
            stack[sp++] = start
            label[start] = next
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                blob.add(x, y, img[p])
                val nbs = intArrayOf(if (x > 0) p - 1 else -1, if (x < w - 1) p + 1 else -1, if (y > 0) p - w else -1, if (y < h - 1) p + w else -1)
                for (q in nbs) if (q >= 0 && label[q] < 0 && img[q] >= threshold) {
                    label[q] = next
                    stack[sp++] = q
                }
            }
            next++
            if (best == null || blob.area > best.area) best = blob
        }
        val b = best ?: return null
        if (b.area < 5) return null
        val cx = b.sx.toFloat() / b.area
        val cy = b.sy.toFloat() / b.area
        val r = sqrt(b.area / PI.toFloat())
        if (r > min(w, h) * 0.35f) return null

        // Fill and compactness: most of the circle is bright, most of the blob lies in the circle.
        var inside = 0
        var insideBright = 0
        var ring = 0L
        var ringN = 0
        val reach = (r * 2.5f).toInt() + 1
        for (y in max(0, (cy - reach).toInt())..min(h - 1, (cy + reach).toInt())) {
            for (x in max(0, (cx - reach).toInt())..min(w - 1, (cx + reach).toInt())) {
                val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
                val v = img[y * w + x]
                if (d <= r * 0.9f) {
                    inside++
                    if (v >= threshold) insideBright++
                } else if (d >= r * 1.6f && d <= r * 2.5f) {
                    ring += v
                    ringN++
                }
            }
        }
        if (inside == 0 || insideBright < inside * 0.8f) return null
        val outlier = b.outsideRadius(cx, cy, r * 1.35f)
        if (outlier > b.area * 0.08f) return null
        val ringMean = if (ringN > 0) ring.toFloat() / ringN else mean
        val contrast = (b.sv.toFloat() / b.area) / max(1f, ringMean)
        if (contrast < 3f) return null
        return Detection(cx / w, cy / h, r / min(w, h), contrast)
    }

    private class Blob {
        var area = 0
        var sx = 0L
        var sy = 0L
        var sv = 0L
        private val xs = ArrayList<Int>()
        private val ys = ArrayList<Int>()

        fun add(x: Int, y: Int, v: Int) {
            area++; sx += x; sy += y; sv += v
            xs += x; ys += y
        }

        fun outsideRadius(cx: Float, cy: Float, r: Float): Int {
            var n = 0
            for (i in xs.indices) {
                val dx = xs[i] - cx
                val dy = ys[i] - cy
                if (dx * dx + dy * dy > r * r) n++
            }
            return n
        }
    }
}
