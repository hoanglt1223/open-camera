/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import dev.opencamera.ml.ImageTensors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Illuminant estimates on packed ARGB pixels, in linear light; pure (unit-tested). Each returns
 * per-channel gains (r, g, b) that make the estimated illuminant neutral, normalized so the
 * luminance of grey stays the same and clamped to a sane range.
 */
object WhiteBalanceMath {
    private val LIN = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linear(c: Int) = LIN[c and 0xff]

    /** Skips clipped and near-black pixels, which carry no illuminant information. */
    private inline fun forUsable(px: IntArray, f: (Float, Float, Float) -> Unit) {
        for (c in px) {
            val r = c shr 16 and 0xff
            val g = c shr 8 and 0xff
            val b = c and 0xff
            if (max(r, max(g, b)) >= 250 || r + g + b < 15) continue
            f(LIN[r], LIN[g], LIN[b])
        }
    }

    /** Minkowski norm estimate: p = 1 gray world, p = 6 shades of gray. */
    fun shadesOfGray(px: IntArray, p: Float): FloatArray? {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        forUsable(px) { lr, lg, lb ->
            r += lr.toDouble().pow(p.toDouble()); g += lg.toDouble().pow(p.toDouble()); b += lb.toDouble().pow(p.toDouble()); n++
        }
        if (n < 16) return null
        val k = 1.0 / p
        return gains(r.pow(k).toFloat(), g.pow(k).toFloat(), b.pow(k).toFloat())
    }

    fun grayWorld(px: IntArray) = shadesOfGray(px, 1f)

    /** White patch: the 99th percentile of each channel is the illuminant. */
    fun whitePatch(px: IntArray): FloatArray? {
        val hist = Array(3) { IntArray(256) }
        var n = 0
        for (c in px) {
            val r = c shr 16 and 0xff; val g = c shr 8 and 0xff; val b = c and 0xff
            if (max(r, max(g, b)) >= 252) continue
            hist[0][r]++; hist[1][g]++; hist[2][b]++; n++
        }
        if (n < 16) return null
        val q = FloatArray(3) { ch ->
            var acc = 0
            var v = 255
            for (i in 255 downTo 0) {
                acc += hist[ch][i]
                if (acc >= n / 100) { v = i; break }
            }
            LIN[v]
        }
        return gains(q[0], q[1], q[2])
    }

    /** Gray edge (van de Weijer): Minkowski p = 6 of gradient magnitudes per channel. */
    fun grayEdge(px: IntArray, w: Int, h: Int, p: Float = 6f): FloatArray? {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = px[y * w + x]
            if (max(c shr 16 and 0xff, max(c shr 8 and 0xff, c and 0xff)) >= 250) continue
            val e = px[y * w + x + 1]; val s = px[(y + 1) * w + x]
            fun grad(sh: Int) = abs(LIN[e shr sh and 0xff] - LIN[c shr sh and 0xff]) + abs(LIN[s shr sh and 0xff] - LIN[c shr sh and 0xff])
            r += grad(16).toDouble().pow(p.toDouble()); g += grad(8).toDouble().pow(p.toDouble()); b += grad(0).toDouble().pow(p.toDouble()); n++
        }
        if (n < 16 || r <= 0 || g <= 0 || b <= 0) return null
        val k = 1.0 / p
        return gains(r.pow(k).toFloat(), g.pow(k).toFloat(), b.pow(k).toFloat())
    }

    /** Tapped neutral: the mean of [px] (a small patch) should be grey. */
    fun neutral(px: IntArray): FloatArray? {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        forUsable(px) { lr, lg, lb -> r += lr; g += lg; b += lb; n++ }
        if (n == 0) return null
        return gains((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    /** Gains that map illuminant (r, g, b) to grey with the same luminance, clamped to [0.4, 2.5]. */
    fun gains(r: Float, g: Float, b: Float): FloatArray? {
        if (r <= 1e-6f || g <= 1e-6f || b <= 1e-6f) return null
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return floatArrayOf((y / r).coerceIn(0.4f, 2.5f), (y / g).coerceIn(0.4f, 2.5f), (y / b).coerceIn(0.4f, 2.5f))
    }

    /** Per-channel 8-bit lookup tables applying [gains] in linear light, blended by [strength]. */
    fun luts(gains: FloatArray, strength: Float): Array<IntArray> = Array(3) { ch ->
        val k = 1f + (gains[ch] - 1f) * strength.coerceIn(0f, 1f)
        IntArray(256) { i ->
            val l = (LIN[i] * k).coerceIn(0f, 1f)
            val s = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
            (s * 255f).roundToInt().coerceIn(0, 255)
        }
    }
}

/**
 * White balance: tap a neutral point, gray world, white patch, shades of gray or gray edge. The
 * estimate runs on a 512 px copy and the gains are stored in the params, so preview and export
 * match. FFCC needs trained parameters the project does not have (see docs/models.md).
 */
class WhiteBalanceTool : RetouchTool {
    override val id = "whitebalance"
    override val input = InputKind.TAP
    override val defaultStrength = 1f

    override fun methods() = listOf(GRAY_WORLD, WHITE_PATCH, SHADES_OF_GRAY, GRAY_EDGE, TAP).map { RetouchMethod(it, true) }

    fun estimate(image: Bitmap, method: String, tap: NPoint?): FloatArray? {
        val k = 512f / max(image.width, image.height)
        val small = if (k < 1f) ImageTensors.scaled(image, (image.width * k).roundToInt().coerceAtLeast(1), (image.height * k).roundToInt().coerceAtLeast(1)) else image
        try {
            val px = ImageTensors.pixels(small)
            val w = small.width
            val h = small.height
            return when (method) {
                TAP -> {
                    val t = tap ?: return null
                    val cx = (t.x * w).toInt().coerceIn(0, w - 1)
                    val cy = (t.y * h).toInt().coerceIn(0, h - 1)
                    val r = max(2, w / 100)
                    val patch = ArrayList<Int>()
                    for (y in (cy - r).coerceAtLeast(0)..(cy + r).coerceAtMost(h - 1)) {
                        for (x in (cx - r).coerceAtLeast(0)..(cx + r).coerceAtMost(w - 1)) patch += px[y * w + x]
                    }
                    WhiteBalanceMath.neutral(patch.toIntArray())
                }
                WHITE_PATCH -> WhiteBalanceMath.whitePatch(px)
                SHADES_OF_GRAY -> WhiteBalanceMath.shadesOfGray(px, 6f)
                GRAY_EDGE -> WhiteBalanceMath.grayEdge(px, w, h)
                else -> WhiteBalanceMath.grayWorld(px)
            }
        } finally {
            if (small !== image) small.recycle()
        }
    }

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        val method = params.method ?: if (params.taps.isNotEmpty()) TAP else GRAY_WORLD
        val stored = listOf("gr", "gg", "gb").map { params.values[it] }
        val gains = if (stored.all { it != null }) {
            FloatArray(3) { stored[it]!! }
        } else {
            estimate(image, method, params.taps.firstOrNull()) ?: throw RetouchUnavailable("no neutral reference found")
        }
        val luts = WhiteBalanceMath.luts(gains, params.strength)
        val undo = UndoPatch.local(image, android.graphics.Rect(0, 0, image.width, image.height))
        val w = image.width
        val row = IntArray(w)
        for (y in 0 until image.height) {
            image.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                row[x] = (c and -0x1000000) or (luts[0][c shr 16 and 0xff] shl 16) or (luts[1][c shr 8 and 0xff] shl 8) or luts[2][c and 0xff]
            }
            image.setPixels(row, 0, w, 0, y, w, 1)
        }
        val resolved = params.copy(method = method, values = params.values + mapOf("gr" to gains[0], "gg" to gains[1], "gb" to gains[2]))
        return RetouchResult(image, undo, method, note = "%.2f/%.2f/%.2f".format(java.util.Locale.US, gains[0], gains[1], gains[2]), resolved = resolved)
    }

    companion object {
        const val GRAY_WORLD = "gray_world"
        const val WHITE_PATCH = "white_patch"
        const val SHADES_OF_GRAY = "shades_of_gray"
        const val GRAY_EDGE = "gray_edge"
        const val TAP = "tap"
    }
}
