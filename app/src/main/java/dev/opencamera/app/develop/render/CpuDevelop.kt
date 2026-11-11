/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CPU fallback of the develop pipeline (no GL context, context lost, or tests). Uses the same
 * uniforms and [DevelopKernel] as the shader; blurs are box-filter approximations of the
 * Gaussians at the same radii.
 */
object CpuDevelop {
    /** A multi-channel float image. */
    class Plane(val w: Int, val h: Int, val ch: Int, val data: FloatArray = FloatArray(w * h * ch)) {
        fun sample(u: Float, v: Float, out: FloatArray) {
            val x = (u * w - 0.5f).coerceIn(0f, w - 1f)
            val y = (v * h - 0.5f).coerceIn(0f, h - 1f)
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val fx = x - x0; val fy = y - y0
            for (c in 0 until ch) {
                val a = data[(y0 * w + x0) * ch + c] * (1 - fx) + data[(y0 * w + x1) * ch + c] * fx
                val b = data[(y1 * w + x0) * ch + c] * (1 - fx) + data[(y1 * w + x1) * ch + c] * fx
                out[c] = a * (1 - fy) + b * fy
            }
        }
    }

    class Prepared(val lin: Plane, val blurM: Plane, val blurL: Plane)

    /** Linearizes ARGB pixels and computes the medium and large blurs. */
    fun prepare(pixels: IntArray, w: Int, h: Int): Prepared {
        val lin = Plane(w, h, 4)
        val lut = FloatArray(256) { ColorMath.srgbToLinear(it / 255f) }
        for (i in 0 until w * h) {
            val p = pixels[i]
            val r = lut[(p shr 16) and 255]; val g = lut[(p shr 8) and 255]; val b = lut[p and 255]
            lin.data[i * 4] = r; lin.data[i * 4 + 1] = g; lin.data[i * 4 + 2] = b
            lin.data[i * 4 + 3] = min(r, min(g, b))
        }
        val long = max(w, h).toFloat()
        return Prepared(lin, blurred(lin, long / 100f), blurred(lin, long / 25f))
    }

    /** Down-samples so the blur is ~2.5 px, then three box passes (close to a Gaussian). */
    private fun blurred(src: Plane, sigma: Float): Plane {
        var f = 1
        while (sigma / (f * 2) >= 2.5f && src.w / (f * 2) >= 4 && src.h / (f * 2) >= 4) f *= 2
        val w = max(1, src.w / f); val h = max(1, src.h / f)
        val small = Plane(w, h, src.ch)
        for (y in 0 until h) for (x in 0 until w) {
            for (c in 0 until src.ch) {
                var s = 0f
                for (dy in 0 until f) for (dx in 0 until f) {
                    s += src.data[(min(src.h - 1, y * f + dy) * src.w + min(src.w - 1, x * f + dx)) * src.ch + c]
                }
                small.data[(y * w + x) * src.ch + c] = s / (f * f)
            }
        }
        // Three box passes of radius r approximate a Gaussian with sigma^2 = r(r+1).
        val s = sigma / f
        val r = max(1, ((-1 + kotlin.math.sqrt(1 + 4 * s * s)) / 2).roundToInt())
        repeat(3) { boxH(small, r); boxV(small, r) }
        return small
    }

    private fun boxH(p: Plane, r: Int) {
        val tmp = FloatArray(p.w * p.ch)
        for (y in 0 until p.h) {
            for (c in 0 until p.ch) {
                var acc = 0f
                for (k in -r..r) acc += p.data[(y * p.w + k.coerceIn(0, p.w - 1)) * p.ch + c]
                for (x in 0 until p.w) {
                    tmp[x * p.ch + c] = acc / (2 * r + 1)
                    acc += p.data[(y * p.w + min(p.w - 1, x + r + 1)) * p.ch + c] - p.data[(y * p.w + max(0, x - r)) * p.ch + c]
                }
            }
            tmp.copyInto(p.data, y * p.w * p.ch)
        }
    }

    private fun boxV(p: Plane, r: Int) {
        val col = FloatArray(p.h)
        for (x in 0 until p.w) for (c in 0 until p.ch) {
            var acc = 0f
            for (k in -r..r) acc += p.data[(k.coerceIn(0, p.h - 1) * p.w + x) * p.ch + c]
            for (y in 0 until p.h) {
                col[y] = acc / (2 * r + 1)
                acc += p.data[(min(p.h - 1, y + r + 1) * p.w + x) * p.ch + c] - p.data[(max(0, y - r) * p.w + x) * p.ch + c]
            }
            for (y in 0 until p.h) p.data[(y * p.w + x) * p.ch + c] = col[y]
        }
    }

    /**
     * Renders the whole output ([frame]) at [outW] x [outH] into ARGB pixels. [masks] are up to
     * four coverage maps at the source resolution.
     */
    fun render(
        u: DevelopUniforms,
        src: Prepared,
        frame: Geometry.Frame,
        outW: Int,
        outH: Int,
        masks: List<FloatArray>,
        threads: Int = Runtime.getRuntime().availableProcessors(),
    ): IntArray {
        val out = IntArray(outW * outH)
        val lin = src.lin
        val sigma = max(1f, max(lin.w, lin.h) / GlDevelop.SMALL_BLUR_DIVISOR)
        val du = sigma / lin.w
        val dv = sigma / lin.h
        val rows = (0 until outH).chunked(max(1, outH / (threads * 4)))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            rows.map { chunk ->
                pool.submit {
                    val px = DevelopKernel.Pixel()
                    val q = FloatArray(2)
                    val t = FloatArray(4)
                    for (y in chunk) for (x in 0 until outW) {
                        val ou = (x + 0.5f) / outW
                        val ov = (y + 0.5f) / outH
                        frame.outputToSource.map(ou, ov, q)
                        val gu = q[0]; val gv = q[1]
                        if (gu < 0f || gv < 0f || gu > 1f || gv > 1f) {
                            out[y * outW + x] = 0xff000000.toInt()
                            continue
                        }
                        lin.sample(gu, gv, t); px.lin[0] = t[0]; px.lin[1] = t[1]; px.lin[2] = t[2]
                        px.bs.fill(0f)
                        for ((ox, oy, wgt) in TAPS) {
                            lin.sample(gu + ox * du, gv + oy * dv, t)
                            px.bs[0] += t[0] * wgt; px.bs[1] += t[1] * wgt; px.bs[2] += t[2] * wgt
                        }
                        src.blurM.sample(gu, gv, t); px.bm[0] = t[0]; px.bm[1] = t[1]; px.bm[2] = t[2]
                        src.blurL.sample(gu, gv, px.bl)
                        if (masks.isNotEmpty()) {
                            val mx = (gu * lin.w).toInt().coerceIn(0, lin.w - 1)
                            val my = (gv * lin.h).toInt().coerceIn(0, lin.h - 1)
                            for (k in 0 until 4) px.mask[k] = masks.getOrNull(k)?.get(my * lin.w + mx) ?: 0f
                        }
                        DevelopKernel.shade(u, px, ou, ov, outW.toFloat(), outH.toFloat())
                        out[y * outW + x] = (0xff shl 24) or (q8(px.out[0]) shl 16) or (q8(px.out[1]) shl 8) or q8(px.out[2])
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return out
    }

    private fun q8(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private data class Tap(val x: Float, val y: Float, val w: Float)

    /** Same 3x3 taps as the shader's fine-detail blur. */
    private val TAPS = listOf(
        Tap(0f, 0f, 0.25f),
        Tap(1f, 0f, 0.125f), Tap(-1f, 0f, 0.125f), Tap(0f, 1f, 0.125f), Tap(0f, -1f, 0.125f),
        Tap(1f, 1f, 0.0625f), Tap(-1f, -1f, 0.0625f), Tap(1f, -1f, 0.0625f), Tap(-1f, 1f, 0.0625f),
    )
}
