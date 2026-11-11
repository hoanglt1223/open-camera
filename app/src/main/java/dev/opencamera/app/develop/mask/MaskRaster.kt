/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.mask

import dev.opencamera.app.develop.model.AiShape
import dev.opencamera.app.develop.model.BrushShape
import dev.opencamera.app.develop.model.ColorRangeShape
import dev.opencamera.app.develop.model.LinearShape
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.model.LuminanceRangeShape
import dev.opencamera.app.develop.model.MaskOp
import dev.opencamera.app.develop.model.MaskShape
import dev.opencamera.app.develop.model.RadialShape
import dev.opencamera.app.develop.model.Stroke
import dev.opencamera.app.develop.render.ColorMath
import dev.opencamera.app.develop.render.DevelopKernel.smoothstep
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders vector masks to coverage maps at any resolution. Geometry is stored normalized to the
 * upright source, so the same mask renders identically for the proxy and for an export.
 */
object MaskRaster {
    /** Coverage of one mask (0..1 per pixel). [pixels] are the source ARGB pixels (for range masks). */
    fun render(
        mask: LocalMask,
        w: Int,
        h: Int,
        pixels: IntArray?,
        ai: (AiShape) -> FloatArray?,
    ): FloatArray {
        val cov = FloatArray(w * h)
        var first = true
        for (c in mask.components) {
            val layer = shape(c.shape, w, h, pixels, ai) ?: continue
            if (c.invert) for (i in layer.indices) layer[i] = 1f - layer[i]
            when {
                first && c.op != MaskOp.SUBTRACT -> layer.copyInto(cov)
                c.op == MaskOp.ADD -> for (i in cov.indices) cov[i] = max(cov[i], layer[i])
                c.op == MaskOp.SUBTRACT -> for (i in cov.indices) cov[i] *= 1f - layer[i]
                c.op == MaskOp.INTERSECT -> for (i in cov.indices) cov[i] *= layer[i]
            }
            first = false
        }
        return cov
    }

    /** Packs up to four coverage maps into RGBA8 for the shader. */
    fun pack(maps: List<FloatArray>, w: Int, h: Int): ByteBuffer {
        val bytes = ByteArray(w * h * 4)
        for ((k, m) in maps.take(4).withIndex()) {
            var o = k
            for (i in 0 until w * h) {
                bytes[o] = (m[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                o += 4
            }
        }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.position(0) }
    }

    fun shape(s: MaskShape, w: Int, h: Int, pixels: IntArray?, ai: (AiShape) -> FloatArray?): FloatArray? = when (s) {
        is BrushShape -> brush(s.strokes, w, h)
        is LinearShape -> linear(s, w, h)
        is RadialShape -> radial(s, w, h)
        is LuminanceRangeShape -> pixels?.let { luminance(s, it) }
        is ColorRangeShape -> pixels?.let { color(s, it) }
        is AiShape -> ai(s)?.copyOf()
    }

    fun brush(strokes: List<Stroke>, w: Int, h: Int): FloatArray {
        val cov = FloatArray(w * h)
        val long = max(w, h).toFloat()
        for (st in strokes) {
            val r = max(0.5f, st.radius * long)
            val inner = r * (1f - st.feather.coerceIn(0f, 1f))
            val pts = st.points
            val n = pts.size / 2
            if (n == 0) continue
            for (seg in 0 until max(1, n - 1)) {
                val ax = pts[seg * 2] * w; val ay = pts[seg * 2 + 1] * h
                val bx = if (n > 1) pts[seg * 2 + 2] * w else ax
                val by = if (n > 1) pts[seg * 2 + 3] * h else ay
                val x0 = max(0, (min(ax, bx) - r).toInt()); val x1 = min(w - 1, (max(ax, bx) + r).toInt() + 1)
                val y0 = max(0, (min(ay, by) - r).toInt()); val y1 = min(h - 1, (max(ay, by) + r).toInt() + 1)
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                for (y in y0..y1) for (x in x0..x1) {
                    val px = x + 0.5f; val py = y + 0.5f
                    val t = if (len2 > 0f) (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
                    val qx = ax + t * dx - px; val qy = ay + t * dy - py
                    val d = sqrt(qx * qx + qy * qy)
                    if (d >= r) continue
                    val c = st.flow * (1f - smoothstep(inner, r, d))
                    val i = y * w + x
                    cov[i] = if (st.erase) cov[i] * (1f - c) else max(cov[i], c)
                }
            }
        }
        return cov
    }

    fun linear(s: LinearShape, w: Int, h: Int): FloatArray {
        val ax = s.x0 * w; val ay = s.y0 * h
        val dx = s.x1 * w - ax; val dy = s.y1 * h - ay
        val len2 = max(1e-6f, dx * dx + dy * dy)
        return FloatArray(w * h) { i ->
            val x = i % w + 0.5f; val y = i / w + 0.5f
            1f - smoothstep(0f, 1f, ((x - ax) * dx + (y - ay) * dy) / len2)
        }
    }

    fun radial(s: RadialShape, w: Int, h: Int): FloatArray {
        val long = max(w, h).toFloat()
        val cx = s.cx * w; val cy = s.cy * h
        val rx = max(1f, s.rx * long); val ry = max(1f, s.ry * long)
        val c = cos(Math.toRadians(s.angle.toDouble())).toFloat()
        val sn = sin(Math.toRadians(s.angle.toDouble())).toFloat()
        val inner = 1f - s.feather.coerceIn(0f, 1f)
        return FloatArray(w * h) { i ->
            val x = i % w + 0.5f - cx; val y = i / w + 0.5f - cy
            val u = (x * c + y * sn) / rx
            val v = (-x * sn + y * c) / ry
            1f - smoothstep(inner, 1f, sqrt(u * u + v * v))
        }
    }

    fun luminance(s: LuminanceRangeShape, px: IntArray): FloatArray = FloatArray(px.size) { i ->
        val p = px[i]
        val l = ColorMath.luma(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f)
        val sm = max(1e-3f, s.smooth)
        smoothstep(s.lo - sm, s.lo, l) * (1f - smoothstep(s.hi, s.hi + sm, l))
    }

    fun color(s: ColorRangeShape, px: IntArray): FloatArray {
        val tol = s.tolerance.coerceIn(0.02f, 1f) * 60f
        return FloatArray(px.size) { i ->
            val p = px[i]
            val r = ((p shr 16) and 255) / 255f; val g = ((p shr 8) and 255) / 255f; val b = (p and 255) / 255f
            val sat = max(r, max(g, b)) - min(r, min(g, b))
            var d = abs(ColorMath.hue(r, g, b) - s.hue)
            if (d > 180f) d = 360f - d
            (1f - smoothstep(tol, tol + 15f, d)) * smoothstep(0.04f, 0.12f, sat)
        }
    }

    /**
     * Edge-aware refinement of a low-resolution segmentation (guided filter, He et al. 2010)
     * using the image luminance as guide. Box filters use summed-area tables, so cost is O(n).
     */
    fun guidedFilter(guide: FloatArray, input: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val meanI = box(guide, w, h, radius)
        val meanP = box(input, w, h, radius)
        val corrIP = box(FloatArray(w * h) { guide[it] * input[it] }, w, h, radius)
        val varI = box(FloatArray(w * h) { guide[it] * guide[it] }, w, h, radius)
        val a = FloatArray(w * h)
        val b = FloatArray(w * h)
        for (i in a.indices) {
            val cov = corrIP[i] - meanI[i] * meanP[i]
            val v = varI[i] - meanI[i] * meanI[i]
            a[i] = cov / (v + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val ma = box(a, w, h, radius)
        val mb = box(b, w, h, radius)
        return FloatArray(w * h) { (ma[it] * guide[it] + mb[it]).coerceIn(0f, 1f) }
    }

    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val sat = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val s = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        return out
    }

    /** Bilinear resample of a coverage map. */
    fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray = FloatArray(dw * dh) { i ->
        val x = ((i % dw + 0.5f) * sw / dw - 0.5f).coerceIn(0f, sw - 1f)
        val y = ((i / dw + 0.5f) * sh / dh - 0.5f).coerceIn(0f, sh - 1f)
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
        val fx = x - x0; val fy = y - y0
        val a = src[y0 * sw + x0] * (1 - fx) + src[y0 * sw + x1] * fx
        val b = src[y1 * sw + x0] * (1 - fx) + src[y1 * sw + x1] * fx
        a * (1 - fy) + b * fy
    }
}
