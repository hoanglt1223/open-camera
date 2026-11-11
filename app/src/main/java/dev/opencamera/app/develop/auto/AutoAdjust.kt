/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.auto

import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.render.ColorMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One-tap Auto from image statistics on the proxy. Results are ordinary slider values, so the
 * user can adjust them afterwards.
 */
object AutoAdjust {
    /** Subsampled linear pixels used by every estimate. */
    class Samples(val r: FloatArray, val g: FloatArray, val b: FloatArray) {
        val size get() = r.size
    }

    fun samples(pixels: IntArray, maxCount: Int = 262_144): Samples {
        val step = max(1, pixels.size / maxCount)
        val n = pixels.size / step
        val lut = FloatArray(256) { ColorMath.srgbToLinear(it / 255f) }
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i * step]
            r[i] = lut[(p shr 16) and 255]; g[i] = lut[(p shr 8) and 255]; b[i] = lut[p and 255]
        }
        return Samples(r, g, b)
    }

    /** Auto tone: exposure, contrast, highlights, shadows, whites and blacks. */
    fun tone(s: Samples): Map<String, Float> {
        val n = s.size
        if (n == 0) return emptyMap()
        val lum = FloatArray(n) { max(1e-5f, ColorMath.luma(s.r[it], s.g[it], s.b[it])) }
        val sorted = lum.sortedArray()
        val median = sorted[n / 2]
        val ev = (log2(MID_TARGET / median) * 0.8f).coerceIn(-2f, 2f)
        val gain = 2f.pow(ev)
        val enc = FloatArray(n) { ColorMath.linearToSrgb(sorted[it] * gain) }
        val p005 = enc[(n * 0.005f).toInt().coerceIn(0, n - 1)]
        val p995 = enc[(n * 0.995f).toInt().coerceIn(0, n - 1)]
        val whites = ((1f - p995 / 0.97f) / 0.12f * 100f).coerceIn(-60f, 60f)
        val bp = (p005 - 0.02f) / 0.98f
        val blacks = (-bp / 0.06f * 100f).coerceIn(-60f, 40f)
        var mean = 0.0
        for (v in lum) mean += ln(v * gain.toDouble())
        mean /= n
        var varSum = 0.0
        for (v in lum) { val d = ln(v * gain.toDouble()) - mean; varSum += d * d }
        val stdEv = (sqrt(varSum / n) / ln(2.0)).toFloat()
        val contrast = ((1.6f - stdEv) / 1.6f * 50f).coerceIn(-30f, 35f)
        val clipped = enc.count { it > 0.98f }.toFloat() / n
        val dark = enc.count { it < 0.12f }.toFloat() / n
        val highlights = if (clipped > 0.01f) (-clipped * 1500f).coerceIn(-70f, 0f) else 0f
        val shadows = if (dark > 0.15f) (dark * 120f).coerceIn(0f, 50f) else 0f
        return mapOf(
            Params.EXPOSURE to round2(ev),
            Params.CONTRAST to contrast.roundToInt().toFloat(),
            Params.HIGHLIGHTS to highlights.roundToInt().toFloat(),
            Params.SHADOWS to shadows.roundToInt().toFloat(),
            Params.WHITES to whites.roundToInt().toFloat(),
            Params.BLACKS to blacks.roundToInt().toFloat(),
        )
    }

    /**
     * Gray-world white balance over mid-tone, moderately saturated pixels. Searches temperature
     * and tint whose matrix makes the mean neutral; small casts (< 2%) are left alone and the
     * correction is applied at 75% so ambience survives.
     */
    fun whiteBalance(s: Samples, wide: Boolean = false): Map<String, Float> {
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var count = 0
        for (i in 0 until s.size) {
            val r = s.r[i]; val g = s.g[i]; val b = s.b[i]
            val y = ColorMath.luma(r, g, b)
            if (y < 0.02f || y > 0.8f) continue
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            if (mx > 0.97f || (mx - mn) / mx > 0.6f) continue
            sr += r; sg += g; sb += b; count++
        }
        if (count < 64) return emptyMap()
        val mean = floatArrayOf((sr / count).toFloat(), (sg / count).toFloat(), (sb / count).toFloat())
        val cast = (max(mean[0], max(mean[1], mean[2])) - min(mean[0], min(mean[1], mean[2]))) / max(1e-4f, mean[1])
        if (cast < 0.02f) return mapOf(Params.TEMP to 0f, Params.TINT to 0f)
        var best = 0f to 0f
        var bestErr = Float.MAX_VALUE
        fun err(t: Float, ti: Float): Float {
            val m = ColorMath.whiteBalance(t, ti, wide).m
            val r = m[0] * mean[0] + m[1] * mean[1] + m[2] * mean[2]
            val g = m[3] * mean[0] + m[4] * mean[1] + m[5] * mean[2]
            val b = m[6] * mean[0] + m[7] * mean[1] + m[8] * mean[2]
            return (abs(r - g) + abs(b - g)) / max(1e-4f, g)
        }
        var step = 0.1f
        var ct = 0f; var cti = 0f
        repeat(3) {
            for (i in -10..10) for (j in -10..10) {
                val t = (ct + i * step).coerceIn(-1f, 1f)
                val ti = (cti + j * step).coerceIn(-1f, 1f)
                val e = err(t, ti)
                if (e < bestErr) { bestErr = e; best = t to ti }
            }
            ct = best.first; cti = best.second
            step /= 8f
        }
        return mapOf(
            Params.TEMP to (best.first * 75f).roundToInt().toFloat(),
            Params.TINT to (best.second * 75f).roundToInt().toFloat(),
        )
    }

    fun vibrance(s: Samples): Map<String, Float> {
        var sum = 0f
        for (i in 0 until s.size) {
            val r = ColorMath.linearToSrgb(s.r[i]); val g = ColorMath.linearToSrgb(s.g[i]); val b = ColorMath.linearToSrgb(s.b[i])
            sum += max(r, max(g, b)) - min(r, min(g, b))
        }
        val mean = sum / max(1, s.size)
        return mapOf(Params.VIBRANCE to ((0.25f - mean) * 200f).coerceIn(0f, 40f).roundToInt().toFloat())
    }

    fun all(pixels: IntArray, wide: Boolean = false): Map<String, Float> {
        val s = samples(pixels)
        return tone(s) + whiteBalance(s, wide) + vibrance(s)
    }

    /**
     * Auto straighten: dominant near-horizontal or near-vertical edge direction (gradient
     * orientation histogram, magnitude weighted) within +-[MAX_TILT] degrees. Returns the crop
     * angle that levels it, or 0 when no clear peak exists.
     */
    fun straighten(pixels: IntArray, w: Int, h: Int): Float {
        val step = max(1, max(w, h) / 640)
        val sw = w / step; val sh = h / step
        if (sw < 8 || sh < 8) return 0f
        val l = FloatArray(sw * sh) { i ->
            val p = pixels[(i / sw) * step * w + (i % sw) * step]
            ColorMath.luma(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f)
        }
        // Two 3x3 box passes: staircase edges of aliased lines would otherwise vote for 0 and 45 degrees.
        repeat(2) {
            val c = l.copyOf()
            for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
                var s = 0f
                for (dy in -1..1) for (dx in -1..1) s += c[(y + dy) * sw + x + dx]
                l[y * sw + x] = s / 9f
            }
        }
        val bins = FloatArray((MAX_TILT * 2 * 10).toInt() + 1)
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            val gx = (l[i - sw + 1] + 2 * l[i + 1] + l[i + sw + 1]) - (l[i - sw - 1] + 2 * l[i - 1] + l[i + sw - 1])
            val gy = (l[i + sw - 1] + 2 * l[i + sw] + l[i + sw + 1]) - (l[i - sw - 1] + 2 * l[i - sw] + l[i - sw + 1])
            val mag = hypot(gx, gy)
            if (mag < 0.05f) continue
            // Edge direction is perpendicular to the gradient; fold to the nearest axis.
            var a = Math.toDegrees(atan2(gy, gx).toDouble()).toFloat() + 90f
            a = ((a % 90f) + 90f) % 90f
            if (a > 45f) a -= 90f
            if (abs(a) > MAX_TILT) continue
            bins[((a + MAX_TILT) * 10f).roundToInt()] += mag
        }
        val smooth = FloatArray(bins.size) { i -> (-3..3).sumOf { k -> bins.getOrElse(i + k) { 0f }.toDouble() }.toFloat() }
        val peak = smooth.indices.maxByOrNull { smooth[it] } ?: return 0f
        val total = smooth.sum()
        if (total <= 0f || smooth[peak] / total < 0.02f) return 0f
        val angle = peak / 10f - MAX_TILT
        return if (abs(angle) < 0.2f) 0f else -angle
    }

    /**
     * Haze colour for dehaze: mean of the brightest 0.1% of the dark channel (He et al. 2009).
     */
    fun atmosphere(s: Samples): FloatArray {
        if (s.size == 0) return floatArrayOf(1f, 1f, 1f)
        // Threshold for the top 0.1% from a histogram (no sort of the whole image).
        val bins = IntArray(1024)
        val dark = FloatArray(s.size) { min(s.r[it], min(s.g[it], s.b[it])) }
        for (d in dark) bins[(d.coerceIn(0f, 1f) * 1023f).toInt()]++
        val want = max(1, s.size / 1000)
        var acc = 0
        var cut = 1023
        while (cut > 0 && acc + bins[cut] < want) acc += bins[cut--]
        val threshold = cut / 1023f
        val a = FloatArray(3)
        var n = 0
        for (i in 0 until s.size) if (dark[i] >= threshold) { a[0] += s.r[i]; a[1] += s.g[i]; a[2] += s.b[i]; n++ }
        for (c in 0..2) a[c] = max(0.05f, a[c] / max(1, n))
        return a
    }

    private fun log2(v: Float) = (ln(v.toDouble()) / ln(2.0)).toFloat()
    private fun round2(v: Float) = (v * 100f).roundToInt() / 100f

    private const val MID_TARGET = 0.18f
    private const val MAX_TILT = 15f
}

/** Live histogram of the rendered image: 256 bins for R, G, B and luminance. */
class Histogram(val r: IntArray, val g: IntArray, val b: IntArray, val l: IntArray) {
    val max: Int get() = maxOf(r.max(), g.max(), b.max(), l.max()).coerceAtLeast(1)
    val clippedHighlights: Float get() = (r[255] + g[255] + b[255]).toFloat() / (3f * l.sum().coerceAtLeast(1))
    val clippedShadows: Float get() = l[0].toFloat() / l.sum().coerceAtLeast(1)

    companion object {
        fun of(pixels: IntArray): Histogram {
            val r = IntArray(256); val g = IntArray(256); val b = IntArray(256); val l = IntArray(256)
            for (p in pixels) {
                val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
                r[pr]++; g[pg]++; b[pb]++
                l[((54 * pr + 183 * pg + 19 * pb) shr 8).coerceAtMost(255)]++
            }
            return Histogram(r, g, b, l)
        }
    }
}
