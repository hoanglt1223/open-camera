/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Small 8-bit luma image (a downscaled burst frame). */
class LumaFrame(val width: Int, val height: Int, val px: ByteArray) {
    init {
        require(width > 0 && height > 0 && px.size >= width * height)
    }

    operator fun get(x: Int, y: Int) = px[y * width + x].toInt() and 0xff
}

data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2

    fun clampTo(width: Int, height: Int): Box {
        val cw = min(w, width)
        val ch = min(h, height)
        return Box(x.coerceIn(0, width - cw), y.coerceIn(0, height - ch), cw, ch)
    }
}

/**
 * Finds the subject in a burst. The default is motion based; a segmentation model can provide
 * another implementation. [hint] is a normalised point the user selected (tap to focus), if any.
 */
fun interface SubjectLocator {
    fun locate(frames: List<LumaFrame>, hint: Pair<Float, Float>?): Box?
}

/**
 * Largest moving region against the per-pixel median background of the burst, or a box around
 * the selected point.
 */
object MotionSubjectLocator : SubjectLocator {
    override fun locate(frames: List<LumaFrame>, hint: Pair<Float, Float>?): Box? {
        val f0 = frames.firstOrNull() ?: return null
        val w = f0.width
        val h = f0.height
        if (hint != null) {
            val bw = max(8, w / 6)
            val bh = max(8, h / 6)
            return Box((hint.first * w).toInt() - bw / 2, (hint.second * h).toInt() - bh / 2, bw, bh).clampTo(w, h)
        }
        if (frames.size < 3) return null
        val n = frames.size
        val background = ByteArray(w * h)
        val column = IntArray(n)
        for (i in 0 until w * h) {
            for (f in 0 until n) column[f] = frames[f].px[i].toInt() and 0xff
            column.sort()
            background[i] = column[n / 2].toByte()
        }
        // Low-pass both so texture and focus changes between frames do not read as motion.
        val bg = boxBlur(background, w, h, 2)
        val mid = boxBlur(frames[n / 2].px, w, h, 2)
        // Noise threshold from the median absolute difference to the background.
        val diffs = IntArray(256)
        for (i in 0 until w * h) diffs[abs(mid[i] - bg[i])]++
        var acc = 0
        var mad = 0
        while (mad < 255 && acc + diffs[mad] < w * h / 2) acc += diffs[mad++]
        val threshold = max(12, 4 * mad + 4)
        val mask = BooleanArray(w * h) { abs(mid[it] - bg[it]) > threshold }
        val box = largestComponent(mask, w, h) ?: return null
        val pad = max(2, max(box.w, box.h) / 8)
        return Box(box.x - pad, box.y - pad, box.w + 2 * pad, box.h + 2 * pad).clampTo(w, h)
    }

    private fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (d in -r..r) s += src[y * w + (x + d).coerceIn(0, w - 1)].toInt() and 0xff
            tmp[y * w + x] = s
        }
        val out = IntArray(w * h)
        val n = (2 * r + 1) * (2 * r + 1)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (d in -r..r) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s / n
        }
        return out
    }

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): Box? {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var best: Box? = null
        var bestArea = max(4, w * h / 500)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var area = 0
            var x0 = w
            var y0 = h
            var x1 = 0
            var y1 = 0
            while (top > 0) {
                val p = stack[--top]
                area++
                val x = p % w
                val y = p / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && mask[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[top++] = p - 1 }
                if (x < w - 1 && mask[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && mask[p - w] && !seen[p - w]) { seen[p - w] = true; stack[top++] = p - w }
                if (y < h - 1 && mask[p + w] && !seen[p + w]) { seen[p + w] = true; stack[top++] = p + w }
            }
            if (area > bestArea) {
                bestArea = area
                best = Box(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
            }
        }
        return best
    }
}

/** Template tracking by sum of absolute differences, the template refreshed every frame. */
object TemplateTracker {
    /** Boxes for every frame, following [start] (a box in frame [startIndex]) both ways. */
    fun track(frames: List<LumaFrame>, startIndex: Int, start: Box): List<Box> {
        val boxes = arrayOfNulls<Box>(frames.size)
        boxes[startIndex] = start
        for (i in startIndex + 1 until frames.size) boxes[i] = step(frames[i - 1], boxes[i - 1]!!, frames[i])
        for (i in startIndex - 1 downTo 0) boxes[i] = step(frames[i + 1], boxes[i + 1]!!, frames[i])
        return boxes.map { it!! }
    }

    private fun step(prev: LumaFrame, box: Box, next: LumaFrame): Box {
        val radius = max(4, max(box.w, box.h) / 2)
        val stride = if (box.w * box.h > 4096) 2 else 1
        var best = box
        var bestCost = Long.MAX_VALUE
        // Coarse search on a 2 px grid, then refine around the best position.
        for (pass in 0..1) {
            val r = if (pass == 0) radius else 2
            val step = if (pass == 0) 2 else 1
            val center = best
            var dy = -r
            while (dy <= r) {
                var dx = -r
                while (dx <= r) {
                    val cand = Box(center.x + dx, center.y + dy, box.w, box.h)
                    if (cand.x >= 0 && cand.y >= 0 && cand.x + cand.w <= next.width && cand.y + cand.h <= next.height) {
                        val cost = sad(prev, box, next, cand, stride, bestCost)
                        if (cost < bestCost) {
                            bestCost = cost
                            best = cand
                        }
                    }
                    dx += step
                }
                dy += step
            }
        }
        return best
    }

    private fun sad(a: LumaFrame, ab: Box, b: LumaFrame, bb: Box, stride: Int, limit: Long): Long {
        var cost = 0L
        var y = 0
        while (y < ab.h) {
            var x = 0
            while (x < ab.w) {
                cost += abs(a[ab.x + x, ab.y + y] - b[bb.x + x, bb.y + y])
                x += stride
            }
            if (cost >= limit) return cost
            y += stride
        }
        return cost
    }
}

object SportsPicker {
    private fun inset(b: Box): Box {
        val dx = b.w / 5
        val dy = b.h / 5
        return Box(b.x + dx, b.y + dy, max(2, b.w - 2 * dx), max(2, b.h - 2 * dy))
    }

    /** Mean squared Laplacian inside [box]: higher is sharper. */
    fun sharpness(frame: LumaFrame, box: Box): Double {
        var sum = 0.0
        var n = 0
        for (y in max(1, box.y) until min(frame.height - 1, box.y + box.h)) {
            for (x in max(1, box.x) until min(frame.width - 1, box.x + box.w)) {
                val l = 4 * frame[x, y] - frame[x - 1, y] - frame[x + 1, y] - frame[x, y - 1] - frame[x, y + 1]
                sum += l.toDouble() * l
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    /**
     * Indices of the [keep] frames where the subject is sharpest, best first. The subject comes
     * from [locator] (on the middle frame) and is tracked through the burst; without a subject
     * the whole frame is ranked.
     */
    fun pick(
        frames: List<LumaFrame>,
        keep: Int,
        hint: Pair<Float, Float>? = null,
        locator: SubjectLocator = MotionSubjectLocator,
    ): IntArray {
        if (frames.isEmpty()) return IntArray(0)
        val full = Box(0, 0, frames[0].width, frames[0].height)
        val subject = runCatching { locator.locate(frames, hint) }.getOrNull()
        val boxes = if (subject != null) {
            runCatching { TemplateTracker.track(frames, frames.size / 2, subject) }.getOrNull()
        } else {
            null
        } ?: List(frames.size) { full }
        // Score the inner part of the box so background around the subject does not count.
        val scores = frames.indices.map { sharpness(frames[it], if (subject != null) inset(boxes[it]) else boxes[it]) }
        return frames.indices.sortedByDescending { scores[it] }.take(keep.coerceAtLeast(1)).toIntArray()
    }
}
