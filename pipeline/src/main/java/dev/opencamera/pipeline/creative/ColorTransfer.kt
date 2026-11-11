/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import dev.opencamera.pipeline.looks.Lut3d
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/** Colour transfer methods, in increasing quality and cost. */
enum class ColorTransferMethod { REINHARD, HISTOGRAM, SLICED_OT }

/** Oklab colour samples, packed L, a, b. */
class ColorSamples(val lab: FloatArray) {
    val count get() = lab.size / 3

    companion object {
        /** Samples every [step]-th pixel of ARGB [pixels]. */
        fun fromArgb(pixels: IntArray, step: Int = 1): ColorSamples {
            val n = (pixels.size + step - 1) / step
            val out = FloatArray(n * 3)
            var j = 0
            var i = 0
            while (i < pixels.size) {
                val p = pixels[i]
                ColorSpaces.srgbToOklab(
                    ((p shr 16) and 0xff) / 255f, ((p shr 8) and 0xff) / 255f, (p and 0xff) / 255f, out, j,
                )
                j += 3
                i += step
            }
            return ColorSamples(out)
        }
    }
}

/**
 * A colour mapping learnt from a source image towards a reference image, baked into a 3D LUT.
 * The mapping is pointwise in colour, so a LUT reproduces it exactly up to interpolation, and
 * the same LUT drives the preview shader, the still renderer and `.cube` export.
 */
fun interface ColorMap {
    /** Maps Oklab [inp] (3 values at [offset]) into [out] (3 values). */
    fun map(inp: FloatArray, offset: Int, out: FloatArray)
}

object ColorTransfer {
    const val LUT_SIZE = 33
    private const val MIN_SAMPLES = 64

    class Result(val lut: Lut3d, val method: ColorTransferMethod)

    /**
     * Learns [method] from [source] to [reference] and bakes a LUT. Falls back to a simpler
     * method when the inputs are degenerate (too few samples, flat colours, numeric failure),
     * and finally to the identity LUT; never throws for bad image content.
     */
    fun build(
        source: ColorSamples,
        reference: ColorSamples,
        method: ColorTransferMethod,
        skinProtection: Float = 0.7f,
        size: Int = LUT_SIZE,
        seed: Int = 1,
    ): Result {
        val chain = ColorTransferMethod.entries.filter { it.ordinal <= method.ordinal }.reversed()
        for (m in chain) {
            val map = runCatching { learn(source, reference, m, seed) }.getOrNull() ?: continue
            val lut = runCatching { bake(map, skinProtection, size) }.getOrNull() ?: continue
            return Result(lut, m)
        }
        return Result(Lut3d.identity(size), ColorTransferMethod.REINHARD)
    }

    /** Bakes a strength into [lut]: identity at 0, [lut] at 1. */
    fun withIntensity(lut: Lut3d, intensity: Float): Lut3d {
        val k = intensity.coerceIn(0f, 1f)
        val id = Lut3d.identity(lut.size).data
        return Lut3d(lut.size, FloatArray(id.size) { id[it] + (lut.data[it] - id[it]) * k })
    }

    fun learn(source: ColorSamples, reference: ColorSamples, method: ColorTransferMethod, seed: Int = 1): ColorMap? {
        if (source.count < MIN_SAMPLES || reference.count < MIN_SAMPLES) return null
        if (!source.lab.all { it.isFinite() } || !reference.lab.all { it.isFinite() }) return null
        return when (method) {
            ColorTransferMethod.REINHARD -> reinhard(source, reference)
            ColorTransferMethod.HISTOGRAM -> histogram(source, reference)
            ColorTransferMethod.SLICED_OT -> slicedOt(source, reference, seed)
        }
    }

    /** Bakes [map] into a LUT over sRGB input, easing the change on skin tones. */
    fun bake(map: ColorMap, skinProtection: Float, size: Int = LUT_SIZE): Lut3d {
        val data = FloatArray(size * size * size * 3)
        val lab = FloatArray(3)
        val mapped = FloatArray(3)
        val rgb = FloatArray(3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            ColorSpaces.srgbToOklab(r / (size - 1f), g / (size - 1f), b / (size - 1f), lab)
            map.map(lab, 0, mapped)
            val keep = skinProtection.coerceIn(0f, 1f) * skinWeight(lab[0], lab[1], lab[2])
            for (c in 0..2) mapped[c] += (lab[c] - mapped[c]) * keep
            ColorSpaces.oklabToSrgb(mapped[0], mapped[1], mapped[2], rgb)
            for (c in 0..2) {
                val v = rgb[c]
                check(v.isFinite()) { "Non-finite LUT value" }
                data[i++] = v
            }
        }
        return Lut3d(size, data)
    }

    /**
     * Skin likelihood of an Oklab colour (0..1): warm hues of moderate chroma at mid lightness.
     * A colour gate rather than segmentation, so it also works on the preview LUT.
     */
    fun skinWeight(L: Float, a: Float, b: Float): Float {
        val chroma = sqrt(a * a + b * b)
        val hue = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
        val hueW = bump(hue, 25f, 80f, 12f)
        val chromaW = bump(chroma, 0.03f, 0.16f, 0.02f)
        val lW = bump(L, 0.35f, 0.92f, 0.08f)
        return hueW * chromaW * lW
    }

    private fun bump(x: Float, lo: Float, hi: Float, soft: Float): Float {
        val up = ((x - (lo - soft)) / soft).coerceIn(0f, 1f)
        val down = (((hi + soft) - x) / soft).coerceIn(0f, 1f)
        return up * down
    }

    // --- Reinhard: match mean and standard deviation per Oklab channel ---

    private fun reinhard(source: ColorSamples, reference: ColorSamples): ColorMap? {
        val (ms, ss) = stats(source.lab)
        val (mr, sr) = stats(reference.lab)
        val scale = FloatArray(3) { c ->
            if (ss[c] < 1e-4f) 1f else (sr[c] / ss[c]).coerceIn(0.25f, 4f)
        }
        if (ss[0] < 1e-4f && ss[1] < 1e-4f && ss[2] < 1e-4f && ms.contentEquals(mr)) return null
        return ColorMap { inp, o, out ->
            for (c in 0..2) out[c] = (inp[o + c] - ms[c]) * scale[c] + mr[c]
        }
    }

    private fun stats(lab: FloatArray): Pair<FloatArray, FloatArray> {
        val n = lab.size / 3
        val mean = FloatArray(3)
        val sd = FloatArray(3)
        for (c in 0..2) {
            var s = 0.0
            for (i in 0 until n) s += lab[i * 3 + c]
            val m = s / n
            var v = 0.0
            for (i in 0 until n) {
                val d = lab[i * 3 + c] - m
                v += d * d
            }
            mean[c] = m.toFloat()
            sd[c] = sqrt(v / n).toFloat()
        }
        return mean to sd
    }

    // --- Histogram: per-channel quantile matching; lightness only partly, to keep exposure ---

    /** Fraction of the lightness change kept by histogram matching (keeps the target's exposure). */
    const val HISTOGRAM_LIGHTNESS = 0.7f

    private fun histogram(source: ColorSamples, reference: ColorSamples): ColorMap? {
        val maps = (0..2).map { c -> QuantileMap.fit(channel(source.lab, c), channel(reference.lab, c)) ?: return null }
        return ColorMap { inp, o, out ->
            for (c in 0..2) {
                val v = inp[o + c]
                val m = maps[c].map(v)
                out[c] = if (c == 0) v + (m - v) * HISTOGRAM_LIGHTNESS else m
            }
        }
    }

    private fun channel(lab: FloatArray, c: Int) = FloatArray(lab.size / 3) { lab[it * 3 + c] }

    // --- Sliced optimal transport (iterative distribution transfer) ---

    private const val OT_ITERATIONS = 30
    private const val OT_MAX_SAMPLES = 20_000

    private fun slicedOt(source: ColorSamples, reference: ColorSamples, seed: Int): ColorMap? {
        val rnd = Random(seed)
        val src = subsample(source.lab, OT_MAX_SAMPLES, rnd)
        val ref = subsample(reference.lab, OT_MAX_SAMPLES, rnd)
        val ns = src.size / 3
        val nr = ref.size / 3
        val steps = ArrayList<Pair<Array<FloatArray>, Array<QuantileMap>>>(OT_ITERATIONS)
        val ps = FloatArray(ns)
        val pr = FloatArray(nr)
        repeat(OT_ITERATIONS) {
            val basis = randomBasis(rnd)
            val maps = (0..2).map { d ->
                val dir = basis[d]
                for (i in 0 until ns) ps[i] = dot(src, i * 3, dir)
                for (i in 0 until nr) pr[i] = dot(ref, i * 3, dir)
                QuantileMap.fit(ps, pr) ?: return null
            }.toTypedArray()
            for (i in 0 until ns) move(src, i * 3, basis, maps)
            steps += basis to maps
        }
        if (!src.all { it.isFinite() }) return null
        return ColorMap { inp, o, out ->
            out[0] = inp[o]; out[1] = inp[o + 1]; out[2] = inp[o + 2]
            for ((basis, maps) in steps) move(out, 0, basis, maps)
        }
    }

    private fun move(p: FloatArray, o: Int, basis: Array<FloatArray>, maps: Array<QuantileMap>) {
        var dx = 0f; var dy = 0f; var dz = 0f
        for (d in 0..2) {
            val dir = basis[d]
            val t = dot(p, o, dir)
            val delta = maps[d].map(t) - t
            dx += delta * dir[0]; dy += delta * dir[1]; dz += delta * dir[2]
        }
        p[o] += dx; p[o + 1] += dy; p[o + 2] += dz
    }

    private fun dot(p: FloatArray, o: Int, d: FloatArray) = p[o] * d[0] + p[o + 1] * d[1] + p[o + 2] * d[2]

    private fun randomBasis(rnd: Random): Array<FloatArray> {
        val v = Array(3) { FloatArray(3) { gaussian(rnd) } }
        for (i in 0..2) {
            for (j in 0 until i) {
                val d = v[i][0] * v[j][0] + v[i][1] * v[j][1] + v[i][2] * v[j][2]
                for (k in 0..2) v[i][k] -= d * v[j][k]
            }
            val n = sqrt(v[i][0] * v[i][0] + v[i][1] * v[i][1] + v[i][2] * v[i][2])
            if (n < 1e-6f) return arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f))
            for (k in 0..2) v[i][k] /= n
        }
        return v
    }

    private fun gaussian(rnd: Random): Float {
        val u = rnd.nextDouble(1e-9, 1.0)
        val w = rnd.nextDouble()
        return (sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * w)).toFloat()
    }

    private fun subsample(lab: FloatArray, max: Int, rnd: Random): FloatArray {
        val n = lab.size / 3
        if (n <= max) return lab.copyOf()
        val out = FloatArray(max * 3)
        for (i in 0 until max) {
            val j = rnd.nextInt(n)
            out[i * 3] = lab[j * 3]; out[i * 3 + 1] = lab[j * 3 + 1]; out[i * 3 + 2] = lab[j * 3 + 2]
        }
        return out
    }
}

/**
 * Monotone 1D map sending quantiles of a source distribution onto the same quantiles of a
 * reference distribution; piecewise linear, shifted by a constant outside the sampled range.
 */
class QuantileMap private constructor(private val xs: FloatArray, private val ys: FloatArray) {
    fun map(v: Float): Float {
        val n = xs.size
        if (v <= xs[0]) return ys[0] + (v - xs[0])
        if (v >= xs[n - 1]) return ys[n - 1] + (v - xs[n - 1])
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= v) lo = mid else hi = mid
        }
        val span = xs[hi] - xs[lo]
        val t = if (span > 0f) (v - xs[lo]) / span else 0f
        return ys[lo] + (ys[hi] - ys[lo]) * t
    }

    companion object {
        private const val KNOTS = 65

        fun fit(source: FloatArray, reference: FloatArray): QuantileMap? {
            if (source.isEmpty() || reference.isEmpty()) return null
            val s = source.copyOf().also { it.sort() }
            val r = reference.copyOf().also { it.sort() }
            val xs = ArrayList<Float>(KNOTS)
            val ys = ArrayList<Float>(KNOTS)
            for (k in 0 until KNOTS) {
                val q = (k + 0.5f) / KNOTS
                val x = quantile(s, q)
                val y = quantile(r, q)
                // Merge ties so the map stays a function; flat source runs take the mean output.
                if (xs.isNotEmpty() && abs(x - xs.last()) < 1e-6f) {
                    ys[ys.size - 1] = (ys.last() + y) / 2f
                } else {
                    xs += x; ys += y
                }
            }
            for (i in 1 until ys.size) if (ys[i] < ys[i - 1]) ys[i] = ys[i - 1]
            return QuantileMap(xs.toFloatArray(), ys.toFloatArray())
        }

        private fun quantile(sorted: FloatArray, q: Float): Float {
            val pos = q * (sorted.size - 1)
            val i = pos.toInt().coerceIn(0, sorted.size - 1)
            val j = (i + 1).coerceAtMost(sorted.size - 1)
            return sorted[i] + (sorted[j] - sorted[i]) * (pos - i)
        }
    }
}

/** Distribution distance used to grade colour transfer (CIELAB Delta E between quantiles). */
object ColorMetrics {
    private const val QUANTILES = 64

    /**
     * Mean Delta E 1976 between matching per-channel quantiles of two images (ARGB pixels):
     * zero when the colour histograms are identical, independent of pixel positions.
     */
    fun histogramDeltaE(a: IntArray, b: IntArray): Float {
        val qa = quantiles(a)
        val qb = quantiles(b)
        var sum = 0.0
        for (k in 0 until QUANTILES) {
            var d = 0.0
            for (c in 0..2) {
                val x = qa[c][k] - qb[c][k]
                d += x * x
            }
            sum += sqrt(d)
        }
        return (sum / QUANTILES).toFloat()
    }

    private fun quantiles(px: IntArray): Array<FloatArray> {
        val lab = FloatArray(px.size * 3)
        for (i in px.indices) {
            val p = px[i]
            ColorSpaces.srgbToLab(((p shr 16) and 0xff) / 255f, ((p shr 8) and 0xff) / 255f, (p and 0xff) / 255f, lab, i * 3)
        }
        return Array(3) { c ->
            val ch = FloatArray(px.size) { lab[it * 3 + c] }.also { it.sort() }
            FloatArray(QUANTILES) { k -> ch[(((k + 0.5f) / QUANTILES) * (ch.size - 1)).toInt()] }
        }
    }

    /** Mean per-pixel Delta E 1976 between two equally sized images. */
    fun meanDeltaE(a: IntArray, b: IntArray): Float {
        require(a.size == b.size)
        val la = FloatArray(3)
        val lb = FloatArray(3)
        var sum = 0.0
        for (i in a.indices) {
            ColorSpaces.srgbToLab(((a[i] shr 16) and 0xff) / 255f, ((a[i] shr 8) and 0xff) / 255f, (a[i] and 0xff) / 255f, la)
            ColorSpaces.srgbToLab(((b[i] shr 16) and 0xff) / 255f, ((b[i] shr 8) and 0xff) / 255f, (b[i] and 0xff) / 255f, lb)
            sum += sqrt(((la[0] - lb[0]) * (la[0] - lb[0]) + (la[1] - lb[1]) * (la[1] - lb[1]) + (la[2] - lb[2]) * (la[2] - lb[2])).toDouble())
        }
        return (sum / a.size).toFloat()
    }

}
