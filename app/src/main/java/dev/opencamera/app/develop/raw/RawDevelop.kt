/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.raw

import dev.opencamera.app.develop.render.Mat3
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Develops a [DngImage] to scene-linear sRGB: black/white levels, lens-shading gain maps,
 * white balance from AsShotNeutral, demosaic (2x2 binning for previews, Malvar-He-Cutler 2004
 * for export), camera matrix (ForwardMatrix, else ColorMatrix interpolated by the as-shot
 * colour temperature) to XYZ D50 and on to linear sRGB, and BaselineExposure. No Adobe camera
 * profiles are used.
 */
object RawDevelop {
    /**
     * Developed pixels: either float RGB ([rgb]) or, with `toHalf`, RGBA half floats in a direct
     * buffer ([half]) that can go straight into an RGBA_F16 bitmap without using the Java heap.
     */
    class Linear(val width: Int, val height: Int, val rgb: FloatArray?, val half: java.nio.ByteBuffer? = null)

    private class Out(w: Int, h: Int, toHalf: Boolean) {
        val rgb: FloatArray? = if (toHalf) null else FloatArray(w * h * 3)
        val buf: java.nio.ByteBuffer? = if (toHalf) java.nio.ByteBuffer.allocateDirect(w * h * 8).order(java.nio.ByteOrder.nativeOrder()) else null
        private val shorts = buf?.asShortBuffer()

        /** [i] is the pixel index. */
        fun put(i: Int, r: Float, g: Float, b: Float) {
            val f = rgb
            if (f != null) { f[i * 3] = r; f[i * 3 + 1] = g; f[i * 3 + 2] = b; return }
            val s = shorts!!
            s.put(i * 4, toHalf(r)); s.put(i * 4 + 1, toHalf(g)); s.put(i * 4 + 2, toHalf(b)); s.put(i * 4 + 3, ONE)
        }
    }

    private const val ONE: Short = 0x3C00

    /** IEEE 754 binary16 from a non-negative float (clamped to 64, the editor's headroom). */
    fun toHalf(v: Float): Short {
        val f = v.coerceIn(0f, 64f)
        if (f < 6.1035156e-5f) return (f / 5.9604645e-8f + 0.5f).toInt().toShort()
        val bits = java.lang.Float.floatToRawIntBits(f)
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        val mant = bits and 0x7FFFFF
        var h = (exp shl 10) or (mant ushr 13)
        if (mant and 0x1000 != 0) h++ // round half up
        return h.toShort()
    }

    private val XYZ_D50_TO_SRGB = Mat3(floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f,
    ))
    private val BRADFORD = Mat3(floatArrayOf(0.8951f, 0.2664f, -0.1614f, -0.7502f, 1.7135f, 0.0367f, 0.0389f, -0.0685f, 1.0296f))
    private val D50 = floatArrayOf(0.9642f, 1f, 0.8251f)

    fun develop(img: DngImage, half: Boolean, threads: Int = Runtime.getRuntime().availableProcessors(), toHalf: Boolean = false): Linear {
        val neutral = (img.asShotNeutral?.takeIf { it.size >= 3 && it.all { v -> v > 0f } } ?: floatArrayOf(1f, 1f, 1f))
        val inv = FloatArray(3) { 1f / neutral[it] }
        val minInv = inv.min()
        val gain = FloatArray(3) { inv[it] / minInv }
        val matrix = cameraToSrgb(img, neutral, gain)
        val exposure = 2f.pow(img.baselineExposure)
        val w = img.width
        val h = img.height
        val pool = Executors.newFixedThreadPool(threads)
        try {
            if (img.samples == 3) {
                val out = Out(img.cropW, img.cropH, toHalf)
                parallel(pool, img.cropH) { y ->
                    for (x in 0 until img.cropW) {
                        val si = ((y + img.cropY) * w + x + img.cropX) * 3
                        val c = FloatArray(3) { k -> normalize(img, img.data[si + k].code, 0, k) * gain[k] }
                        write(out, y * img.cropW + x, c, matrix, exposure)
                    }
                }
                return Linear(img.cropW, img.cropH, out.rgb, out.buf)
            }
            if (half) return binnedDirect(img, gain, matrix, exposure, pool, toHalf)
            // Normalized, gain-mapped, white-balanced mosaic.
            val m = FloatArray(w * h)
            val color = IntArray(4) { img.cfa[it] }
            parallel(pool, h) { y ->
                for (x in 0 until w) {
                    val pos = (y and 1) * 2 + (x and 1)
                    m[y * w + x] = normalize(img, img.data[y * w + x].code, pos, 0)
                }
            }
            applyGainMaps(img, m)
            parallel(pool, h) { y ->
                for (x in 0 until w) {
                    val i = y * w + x
                    m[i] = min(1f, m[i] * gain[color[(y and 1) * 2 + (x and 1)]])
                }
            }
            return malvar(img, m, matrix, exposure, pool, toHalf)
        } finally {
            pool.shutdown()
        }
    }

    private fun normalize(img: DngImage, v: Int, cfaPos: Int, channel: Int): Float {
        val b = img.black[if (img.samples == 3) min(channel, 3) else cfaPos]
        return ((v - b) / max(1f, img.white - b)).coerceIn(0f, 1f)
    }

    /** Gain of [g] at pixel (x, y) by bilinear interpolation, in float. */
    private fun gainAt(g: GainMap, x: Int, y: Int, w: Int, h: Int): Float {
        val fy = ((y.toFloat() / h - g.originV.toFloat()) / g.spacingV.toFloat()).coerceIn(0f, (g.pointsV - 1).toFloat())
        val fx = ((x.toFloat() / w - g.originH.toFloat()) / g.spacingH.toFloat()).coerceIn(0f, (g.pointsH - 1).toFloat())
        val y0 = min(fy.toInt(), g.pointsV - 1); val y1 = min(y0 + 1, g.pointsV - 1)
        val x0 = min(fx.toInt(), g.pointsH - 1); val x1 = min(x0 + 1, g.pointsH - 1)
        val ty = fy - y0; val tx = fx - x0
        val a = g.gains[y0 * g.pointsH + x0] * (1 - tx) + g.gains[y0 * g.pointsH + x1] * tx
        val b = g.gains[y1 * g.pointsH + x0] * (1 - tx) + g.gains[y1 * g.pointsH + x1] * tx
        return a * (1 - ty) + b * ty
    }

    /** True when [g] covers pixel (x, y) (its rectangle and pitch). */
    private fun covers(g: GainMap, x: Int, y: Int) = y >= g.top && y < g.bottom && x >= g.left && x < g.right &&
        (y - g.top) % g.rowPitch == 0 && (x - g.left) % g.colPitch == 0

    /**
     * Preview path: each 2x2 CFA block becomes one pixel (no full-size float mosaic), with
     * levels, gain maps and white balance applied per sample.
     */
    private fun binnedDirect(img: DngImage, gain: FloatArray, matrix: Mat3, exposure: Float, pool: java.util.concurrent.ExecutorService, toHalf: Boolean): Linear {
        val w = img.width; val h = img.height
        val ow = img.cropW / 2; val oh = img.cropH / 2
        val out = Out(ow, oh, toHalf)
        val black = img.black
        val scale = FloatArray(4) { 1f / max(1f, img.white - black[it]) }
        parallel(pool, oh) { oy ->
            val c = FloatArray(3)
            for (ox in 0 until ow) {
                c.fill(0f)
                var greens = 0
                val bx = img.cropX + ox * 2; val by = img.cropY + oy * 2
                for (dy in 0..1) for (dx in 0..1) {
                    val x = bx + dx; val y = by + dy
                    val pos = (y and 1) * 2 + (x and 1)
                    val col = img.cfa[pos]
                    var v = ((img.data[y * w + x].code - black[pos]) * scale[pos]).coerceIn(0f, 1f)
                    for (g in img.gainMaps) if (covers(g, x, y)) v *= gainAt(g, x, y, w, h)
                    c[col] += min(1f, v * gain[col])
                    if (col == 1) greens++
                }
                if (greens > 1) c[1] /= greens
                write(out, oy * ow + ox, c, matrix, exposure)
            }
        }
        return Linear(ow, oh, out.rgb, out.buf)
    }

    private fun applyGainMaps(img: DngImage, m: FloatArray) {
        val w = img.width
        val h = img.height
        for (g in img.gainMaps) {
            for (y in max(0, g.top) until min(h, g.bottom) step g.rowPitch) {
                for (x in max(0, g.left) until min(w, g.right) step g.colPitch) m[y * w + x] *= gainAt(g, x, y, w, h)
            }
        }
    }

    /** Malvar, He and Cutler, "High-quality linear interpolation for demosaicing", ICASSP 2004. */
    private fun malvar(img: DngImage, m: FloatArray, matrix: Mat3, exposure: Float, pool: java.util.concurrent.ExecutorService, toHalf: Boolean): Linear {
        val w = img.width
        val h = img.height
        val ow = img.cropW
        val oh = img.cropH
        val out = Out(ow, oh, toHalf)
        fun p(x: Int, y: Int) = m[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        parallel(pool, oh) { oy ->
            val c = FloatArray(3)
            val y = oy + img.cropY
            for (ox in 0 until ow) {
                val x = ox + img.cropX
                val col = img.cfa[(y and 1) * 2 + (x and 1)]
                val v = p(x, y)
                val cross = p(x - 1, y) + p(x + 1, y) + p(x, y - 1) + p(x, y + 1)
                val far = p(x - 2, y) + p(x + 2, y) + p(x, y - 2) + p(x, y + 2)
                val diag = p(x - 1, y - 1) + p(x + 1, y - 1) + p(x - 1, y + 1) + p(x + 1, y + 1)
                if (col == 1) {
                    val horiz = img.cfa[(y and 1) * 2 + ((x + 1) and 1)]
                    val farH = p(x - 2, y) + p(x + 2, y)
                    val farV = p(x, y - 2) + p(x, y + 2)
                    // Neighbour colour along the row ("theta") and along the column ("phi").
                    val rowVal = (5 * v + 4 * (p(x - 1, y) + p(x + 1, y)) - diag - farH + 0.5f * farV) / 8f
                    val colVal = (5 * v + 4 * (p(x, y - 1) + p(x, y + 1)) - diag - farV + 0.5f * farH) / 8f
                    c[1] = v
                    if (horiz == 0) { c[0] = rowVal; c[2] = colVal } else { c[2] = rowVal; c[0] = colVal }
                } else {
                    c[col] = v
                    c[1] = (4 * v + 2 * cross - far) / 8f
                    c[2 - col] = (6 * v + 2 * diag - 1.5f * far) / 8f
                }
                for (k in 0..2) c[k] = max(0f, c[k])
                write(out, oy * ow + ox, c, matrix, exposure)
            }
        }
        return Linear(ow, oh, out.rgb, out.buf)
    }

    private fun write(out: Out, i: Int, c: FloatArray, m: Mat3, exposure: Float) {
        val a = m.m
        out.put(i,
            max(0f, (a[0] * c[0] + a[1] * c[1] + a[2] * c[2]) * exposure),
            max(0f, (a[3] * c[0] + a[4] * c[1] + a[5] * c[2]) * exposure),
            max(0f, (a[6] * c[0] + a[7] * c[1] + a[8] * c[2]) * exposure))
    }

    /**
     * White-balanced camera RGB -> linear sRGB, normalized so a white-balanced neutral stays
     * neutral at the same level.
     */
    fun cameraToSrgb(img: DngImage, neutral: FloatArray, gain: FloatArray): Mat3 {
        val toXyz: Mat3 = run {
            val fm1 = img.forwardMatrix1?.takeIf { it.size == 9 }
            if (fm1 != null) {
                val fm2 = img.forwardMatrix2?.takeIf { it.size == 9 }
                val fm = if (fm2 != null) lerp(fm1, fm2, weight(img, neutral)) else fm1
                // ForwardMatrix maps camera values balanced to neutral = (1,1,1); ours are scaled by gain.
                Mat3(fm) * diag(FloatArray(3) { 1f / (gain[it] * neutral[it]) })
            } else {
                val cm1 = img.colorMatrix1?.takeIf { it.size == 9 } ?: return@run identityToXyz()
                val cm2 = img.colorMatrix2?.takeIf { it.size == 9 }
                val cm = if (cm2 != null) lerp(cm1, cm2, weight(img, neutral)) else cm1
                val camToXyz = Mat3(cm).inverse()
                val wx = camToXyz.m[0] * neutral[0] + camToXyz.m[1] * neutral[1] + camToXyz.m[2] * neutral[2]
                val wy = camToXyz.m[3] * neutral[0] + camToXyz.m[4] * neutral[1] + camToXyz.m[5] * neutral[2]
                val wz = camToXyz.m[6] * neutral[0] + camToXyz.m[7] * neutral[1] + camToXyz.m[8] * neutral[2]
                bradford(floatArrayOf(wx / wy, 1f, wz / wy), D50) * camToXyz * diag(FloatArray(3) { 1f / gain[it] })
            }
        }
        val m = XYZ_D50_TO_SRGB * toXyz
        // Normalize: a white-balanced neutral (1,1,1) maps to luminance 1.
        val r = m.m[0] + m.m[1] + m.m[2]
        val g = m.m[3] + m.m[4] + m.m[5]
        val b = m.m[6] + m.m[7] + m.m[8]
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val s = if (y > 1e-6f) 1f / y else 1f
        return Mat3(FloatArray(9) { m.m[it] * s })
    }

    private fun identityToXyz(): Mat3 = XYZ_D50_TO_SRGB.inverse()

    private fun diag(d: FloatArray) = Mat3(floatArrayOf(d[0], 0f, 0f, 0f, d[1], 0f, 0f, 0f, d[2]))

    private fun bradford(src: FloatArray, dst: FloatArray): Mat3 {
        val b = BRADFORD.m
        fun lms(v: FloatArray) = FloatArray(3) { i -> b[i * 3] * v[0] + b[i * 3 + 1] * v[1] + b[i * 3 + 2] * v[2] }
        val s = lms(src); val d = lms(dst)
        return BRADFORD.inverse() * diag(FloatArray(3) { d[it] / s[it] }) * BRADFORD
    }

    private fun lerp(a: FloatArray, b: FloatArray, w1: Float) = FloatArray(9) { a[it] * w1 + b[it] * (1 - w1) }

    /** Weight of matrix 1, from the as-shot colour temperature (inverse-CCT interpolation). */
    private fun weight(img: DngImage, neutral: FloatArray): Float {
        val t1 = illuminantK(img.illuminant1)
        val t2 = illuminantK(img.illuminant2)
        if (t1 == t2) return 1f
        var w = 0.5f
        repeat(4) {
            val cm1 = img.colorMatrix1?.takeIf { it.size == 9 } ?: return 1f
            val cm = img.colorMatrix2?.takeIf { it.size == 9 }?.let { lerp(cm1, it, w) } ?: cm1
            val inv = Mat3(cm).inverse().m
            val x = inv[0] * neutral[0] + inv[1] * neutral[1] + inv[2] * neutral[2]
            val y = inv[3] * neutral[0] + inv[4] * neutral[1] + inv[5] * neutral[2]
            val z = inv[6] * neutral[0] + inv[7] * neutral[1] + inv[8] * neutral[2]
            val sum = x + y + z
            if (sum <= 0f) return w
            val cx = x / sum; val cy = y / sum
            val n = (cx - 0.3320f) / (0.1858f - cy)
            val cct = (449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f).coerceIn(1500f, 25000f)
            w = ((1f / cct - 1f / t2) / (1f / t1 - 1f / t2)).coerceIn(0f, 1f)
        }
        return w
    }

    fun illuminantK(code: Int): Float = when (code) {
        1, 4, 9 -> 5500f
        2 -> 4200f
        3 -> 2850f
        10 -> 6500f
        11 -> 7500f
        12 -> 6430f
        13 -> 5000f
        14 -> 4150f
        15 -> 3450f
        17 -> 2856f
        18 -> 4874f
        19 -> 6774f
        20 -> 5503f
        21 -> 6504f
        22 -> 7504f
        23 -> 5003f
        24 -> 3200f
        else -> 5000f
    }

    private fun parallel(pool: java.util.concurrent.ExecutorService, rows: Int, body: (Int) -> Unit) {
        val chunk = max(1, rows / 64)
        (0 until rows step chunk).map { start ->
            pool.submit { for (y in start until min(rows, start + chunk)) body(y) }
        }.forEach { it.get() }
    }
}
