/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.render

import dev.opencamera.app.develop.model.Params
import dev.opencamera.pipeline.looks.LookMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reference implementation of the develop pixel pipeline, and the CPU fallback. The fragment
 * shader in [DevelopGlsl] is a line-by-line port; keep the two in sync.
 *
 * Fixed order: noise reduction (on the source detail layer) -> white balance -> exposure ->
 * dehaze -> tone (highlights/shadows on an edge-aware base layer, contrast) -> clarity and
 * texture -> highlight shoulder and encode -> whites/blacks -> saturation, vibrance and the HSL
 * mixer -> colour grading -> tone curve -> look LUT -> sharpening -> vignette -> grain.
 * Local masks add their deltas to the global parameters before any step runs.
 */
object DevelopKernel {
    class Pixel {
        val lin = FloatArray(3)
        val bs = FloatArray(3)
        val bm = FloatArray(3)
        val bl = FloatArray(4)
        val mask = FloatArray(4)
        val out = FloatArray(3)
        internal val tmp = FloatArray(3)
    }

    private val L = DevelopUniforms.LOCAL_INDEX
    private val I_EXP = L.getValue(Params.EXPOSURE)
    private val I_CON = L.getValue(Params.CONTRAST)
    private val I_HI = L.getValue(Params.HIGHLIGHTS)
    private val I_SH = L.getValue(Params.SHADOWS)
    private val I_TEMP = L.getValue(Params.TEMP)
    private val I_TINT = L.getValue(Params.TINT)
    private val I_SAT = L.getValue(Params.SATURATION)
    private val I_CLA = L.getValue(Params.CLARITY)
    private val I_SHARP = L.getValue(Params.SHARPEN)
    private val I_NR = L.getValue(Params.NR_LUMA)
    private val I_DH = L.getValue(Params.DEHAZE)
    private const val LOG2_MID = -2.473931f // log2(0.18)

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun log2(v: Float) = (ln(v.toDouble()) / ln(2.0)).toFloat()
    private fun exp2(v: Float) = 2.0.pow(v.toDouble()).toFloat()

    /**
     * Shades one pixel into [Pixel.out] (encoded, 0..1). [u], [v] are normalized output
     * coordinates; [outW] x [outH] the size of the whole output (not the tile).
     */
    fun shade(U: DevelopUniforms, p: Pixel, u: Float, v: Float, outW: Float, outH: Float) {
        val m = p.mask
        fun loc(i: Int): Float {
            if (U.maskCount == 0) return 0f
            var s = 0f
            for (k in 0 until U.maskCount) s += m[k] * U.local[k][i]
            return s
        }
        var r = p.lin[0]; var g = p.lin[1]; var b = p.lin[2]
        val y0 = max(ColorMath.luma(r, g, b), 1e-5f)
        val ys0 = max(ColorMath.luma(p.bs[0], p.bs[1], p.bs[2]), 1e-5f)
        val ym0 = max(ColorMath.luma(p.bm[0], p.bm[1], p.bm[2]), 1e-5f)

        // Colour noise: replace chroma with the mid-blur chroma.
        if (U.nrColor > 0f) {
            val k = ym0
            val s = y0 / k
            r = y0 + ((r - y0) + ((p.bm[0] - k) * s - (r - y0)) * U.nrColor)
            g = y0 + ((g - y0) + ((p.bm[1] - k) * s - (g - y0)) * U.nrColor)
            b = y0 + ((b - y0) + ((p.bm[2] - k) * s - (b - y0)) * U.nrColor)
        }
        // Luminance noise: drop small detail, keep edges.
        val nr = (U.nrLuma + loc(I_NR)).coerceIn(0f, 1f)
        if (nr > 0f) {
            val d = y0 - ys0
            val thr = 0.002f + 0.04f * nr * sqrt(y0)
            val w = nr * (1f - smoothstep(thr, 3f * thr, abs(d)))
            val k = (y0 - w * d) / y0
            r *= k; g *= k; b *= k
        }

        // White balance (global matrix, then local temperature/tint gains) and exposure.
        val wb = U.wb.m
        val lt = loc(I_TEMP); val lti = loc(I_TINT)
        val gr = (1f + 0.2f * lt + 0.1f * lti); val gg = (1f - 0.15f * lti); val gb = (1f - 0.2f * lt + 0.1f * lti)
        val ev = exp2(U.exposure + loc(I_EXP))
        val nr0 = (wb[0] * r + wb[1] * g + wb[2] * b) * gr * ev
        val ng0 = (wb[3] * r + wb[4] * g + wb[5] * b) * gg * ev
        val nb0 = (wb[6] * r + wb[7] * g + wb[8] * b) * gb * ev
        r = nr0; g = ng0; b = nb0
        val bl = p.bl
        var lr = (wb[0] * bl[0] + wb[1] * bl[1] + wb[2] * bl[2]) * gr * ev
        var lg = (wb[3] * bl[0] + wb[4] * bl[1] + wb[5] * bl[2]) * gg * ev
        var lb = (wb[6] * bl[0] + wb[7] * bl[1] + wb[8] * bl[2]) * gb * ev

        // Dehaze (dark channel prior, He et al. 2009) on the scene-linear values.
        val dh = (U.dehaze + loc(I_DH)).coerceIn(-1f, 1f)
        if (dh != 0f) {
            val ar = U.atmosphere[0] * ev; val ag = U.atmosphere[1] * ev; val ab = U.atmosphere[2] * ev
            if (dh > 0f) {
                val amax = max(max(ar, max(ag, ab)), 1e-4f)
                val t = (1f - 0.95f * dh * (bl[3] * ev / amax)).coerceIn(0.1f, 1f)
                r = max(0f, (r - ar) / t + ar); g = max(0f, (g - ag) / t + ag); b = max(0f, (b - ab) / t + ab)
                lr = max(0f, (lr - ar) / t + ar); lg = max(0f, (lg - ag) / t + ag); lb = max(0f, (lb - ab) / t + ab)
            } else {
                val t = 1f + 0.6f * dh
                r = r * t + ar * (1 - t); g = g * t + ag * (1 - t); b = b * t + ab * (1 - t)
                lr = lr * t + ar * (1 - t); lg = lg * t + ag * (1 - t); lb = lb * t + ab * (1 - t)
            }
        }

        // Tone on an edge-aware base layer: where the pixel differs strongly from the large blur
        // (an edge), the pixel itself is the base, which keeps halos down.
        val y = max(ColorMath.luma(r, g, b), 1e-5f)
        val yb = max(ColorMath.luma(lr, lg, lb), 1e-5f)
        val ly = log2(y)
        val lyb = log2(yb)
        val diff = (ly - lyb) / 0.7f
        val w = exp(-diff * diff)
        var base = ly + (lyb - ly) * w
        val detail = ly - base
        val vv = (base - LOG2_MID) / 3f
        base += (U.highlights + loc(I_HI)) * 1.2f * smoothstep(0f, 1f, vv)
        base += (U.shadows + loc(I_SH)) * 1.2f * smoothstep(0f, 1f, -vv)
        base = LOG2_MID + (base - LOG2_MID) * (1f + 0.5f * (U.contrast + loc(I_CON)))
        val y2 = exp2(base + detail)

        // Clarity (mid frequencies) and texture (fine detail) as luminance ratios.
        val wmid = smoothstep(0f, 0.15f, y2) * (1f - smoothstep(0.6f, 1.5f, y2))
        val cla = U.clarity + loc(I_CLA)
        val local = cla * 0.8f * log2(y0 / ym0).coerceIn(-2f, 2f) * wmid +
            U.texture * 0.8f * log2(y0 / ys0).coerceIn(-1f, 1f)
        val k = y2 / y * exp2(local)
        r *= k; g *= k; b *= k

        // Highlight shoulder (only when exposure pushes values past white), then encode.
        val mx = max(r, max(g, b))
        if (U.shoulder && mx > 0.85f) {
            val t = 0.85f + 0.15f * (1f - exp(-(mx - 0.85f) / 0.15f))
            val s = t / mx
            r *= s; g *= s; b *= s
        }
        r = ColorMath.linearToSrgb(r); g = ColorMath.linearToSrgb(g); b = ColorMath.linearToSrgb(b)

        // Whites and blacks set the end points.
        val bp = -0.06f * U.blacks
        val wp = 1f - 0.12f * U.whites
        val inv = 1f / (wp - bp)
        r = (r - bp) * inv; g = (g - bp) * inv; b = (b - bp) * inv

        // Saturation, vibrance and the HSL mixer.
        var ye = ColorMath.luma(r, g, b)
        val hue = ColorMath.hue(r, g, b)
        val chroma = max(r, max(g, b)) - min(r, min(g, b))
        var hueShift = 0f; var satAdj = 0f; var lumAdj = 0f; var skin = 0f
        bandWeights(hue) { band, wgt ->
            hueShift += wgt * U.hsl[band * 3]
            satAdj += wgt * U.hsl[band * 3 + 1]
            lumAdj += wgt * U.hsl[band * 3 + 2]
            if (band == 1) skin = wgt
        }
        hueShift *= smoothstep(0f, 0.05f, chroma)
        var cr = r - ye; var cg = g - ye; var cb = b - ye
        if (hueShift != 0f) {
            val th = Math.toRadians(hueShift.toDouble()).toFloat()
            val c = cos(th); val s = sin(th)
            val n = 0.57735026f
            val dot = (cr + cg + cb) * n
            val xr = n * (cb - cg); val xg = n * (cr - cb); val xb = n * (cg - cr)
            val nr2 = cr * c + xr * s + n * dot * (1 - c)
            val ng2 = cg * c + xg * s + n * dot * (1 - c)
            val nb2 = cb * c + xb * s + n * dot * (1 - c)
            cr = nr2; cg = ng2; cb = nb2
        }
        var f = (1f + U.saturation + loc(I_SAT)) * (1f + satAdj)
        val vib = U.vibrance
        f *= if (vib > 0f) 1f + vib * (1f - smoothstep(0f, 0.5f, chroma)) * (1f - 0.5f * skin) else 1f + vib * 0.8f
        f = max(f, 0f)
        r = ye + cr * f; g = ye + cg * f; b = ye + cb * f
        val lk = 1f + lumAdj * 0.5f * (chroma * 3f).coerceIn(0f, 1f)
        r *= lk; g *= lk; b *= lk

        // Colour grading.
        ye = ColorMath.luma(r, g, b)
        val ws = 1f - smoothstep(U.gradePivot - U.gradeWidth, U.gradePivot, ye)
        val wh = smoothstep(U.gradePivot, U.gradePivot + U.gradeWidth, ye)
        val wm = max(0f, 1f - ws - wh)
        val gw = floatArrayOf(ws, wm, wh, 1f)
        val gd = U.grading
        for (i in 0..3) {
            val wi = gw[i]
            if (wi == 0f) continue
            r += wi * gd[i * 4]; g += wi * gd[i * 4 + 1]; b += wi * gd[i * 4 + 2]
            val l = 1f + wi * gd[i * 4 + 3]
            r *= l; g *= l; b *= l
        }

        // Tone curve.
        if (!U.curveIsIdentity) {
            r = curve(U.curve, r, 0); g = curve(U.curve, g, 1); b = curve(U.curve, b, 2)
        }

        // Look (shared tetrahedral LUT and tone table).
        U.look?.let { look ->
            val o = p.tmp
            r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
            LookMath.sampleLut(look.lut, r, g, b, o)
            val ki = look.intensity
            r += (o[0] - r) * ki; g += (o[1] - g) * ki; b += (o[2] - b) * ki
            r += (LookMath.tone(look.tone, r) - r) * ki
            g += (LookMath.tone(look.tone, g) - g) * ki
            b += (LookMath.tone(look.tone, b) - b) * ki
        }

        // Sharpening on the source detail, converted to encoded units.
        val sh = (U.sharpen + loc(I_SHARP)).coerceIn(0f, 1.5f)
        if (sh > 0f) {
            val rmix = ((U.sharpenRadius - 0.5f) / 2.5f) * 0.25f
            var d = y0 - (ys0 + (ym0 - ys0) * rmix)
            val lim = 0.02f + 0.25f * U.sharpenDetail
            d = d.coerceIn(-lim, lim)
            val em = if (U.sharpenMasking > 0f) {
                smoothstep(U.sharpenMasking * 0.03f, U.sharpenMasking * 0.03f + 0.01f, abs(y0 - ym0))
            } else 1f
            val de = ColorMath.linearToSrgb(y0) - ColorMath.linearToSrgb(max(0f, y0 - d))
            val add = sh * 1.5f * de * em
            r += add; g += add; b += add
        }

        // Vignette over the cropped frame.
        if (U.vignette != 0f) {
            val aspect = outW / outH
            val dx = (u - 0.5f) * 2f
            val dy = (v - 0.5f) * 2f
            val rr = max(0f, U.vignetteRoundness)
            val kx = 1f + (aspect / max(aspect, 1f) - 1f) * rr
            val ky = 1f + (1f / max(aspect, 1f) - 1f) * rr
            val dist = sqrt(dx * dx * kx * kx + dy * dy * ky * ky)
            val inner = 0.4f + U.vignetteMidpoint * 0.8f
            val outer = inner + 0.05f + U.vignetteFeather
            val wv = smoothstep(inner, outer, dist)
            if (U.vignette < 0f) {
                val protect = 1f - U.vignetteHighlights * smoothstep(0.7f, 1f, ColorMath.luma(r, g, b))
                val s = 1f + U.vignette * wv * protect
                r *= s; g *= s; b *= s
            } else {
                val t = U.vignette * wv
                r += (1f - r) * t; g += (1f - g) * t; b += (1f - b) * t
            }
        }

        // Grain: cells sized relative to the output, fixed seed, so preview and export match.
        if (U.grain > 0f) {
            val n = grainNoise(u * outW, v * outH, grainCell(U, outW, outH), U.grainRoughness)
            val yy = ColorMath.luma(r, g, b)
            val gn = n * U.grain * 0.25f * (1f - abs(2f * yy - 1f) * 0.5f)
            r += gn; g += gn; b += gn
        }
        p.out[0] = r.coerceIn(0f, 1f); p.out[1] = g.coerceIn(0f, 1f); p.out[2] = b.coerceIn(0f, 1f)
    }

    fun grainCell(U: DevelopUniforms, outW: Float, outH: Float) =
        max(1f, max(outW, outH) / 1500f * (0.5f + 2.5f * U.grainSize))

    /** Noise in [-0.5, 0.5): bilinear between cell hashes when smooth, nearest when rough. */
    fun grainNoise(x: Float, y: Float, cell: Float, roughness: Float): Float {
        val fx = x / cell - 0.5f
        val fy = y / cell - 0.5f
        val cx = floor(fx).toInt()
        val cy = floor(fy).toInt()
        fun h(i: Int, j: Int) = ((LookMath.hash(i, j) ushr 8) / 16777216f) - 0.5f
        val tx = fx - cx
        val ty = fy - cy
        val smooth = (h(cx, cy) * (1 - tx) + h(cx + 1, cy) * tx) * (1 - ty) + (h(cx, cy + 1) * (1 - tx) + h(cx + 1, cy + 1) * tx) * ty
        val nearest = h(floor(x / cell).toInt(), floor(y / cell).toInt())
        return smooth * 1.6f + (nearest - smooth * 1.6f) * roughness
    }

    private fun curve(t: FloatArray, v: Float, c: Int): Float {
        val pos = v.coerceIn(0f, 1f) * 255f
        val i = min(pos.toInt(), 254)
        val a = t[i * 4 + c]
        return a + (t[(i + 1) * 4 + c] - a) * (pos - i)
    }

    /**
     * Soft band weights for a hue: linear between the two neighbouring band centres, smoothed;
     * they always sum to 1 (a partition of unity, so equal adjustments act like a global one).
     */
    inline fun bandWeights(hue: Float, each: (Int, Float) -> Unit) {
        val c = Params.HSL_CENTERS
        val n = c.size
        var k = n - 1
        for (i in 0 until n) if (hue >= c[i]) k = i
        val next = (k + 1) % n
        val start = c[k]
        val end = if (next == 0) 360f else c[next]
        val h = if (hue < start) hue + 360f else hue
        val t = ((h - start) / (end - start)).coerceIn(0f, 1f)
        val s = t * t * (3 - 2 * t)
        each(k, 1f - s)
        each(next, s)
    }
}
