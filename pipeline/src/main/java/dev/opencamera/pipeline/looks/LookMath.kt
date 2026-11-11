/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reference implementation of the per-pixel look. `look_apply.cpp` and the
 * preview fragment shader in [GlLookProgram] implement the same steps in the
 * same order; keep the three in sync.
 *
 * 1. tetrahedral LUT lookup, 2. mix with the source by intensity,
 * 3. per-channel tone table, 4. vignette, 5. grain.
 */
object LookMath {
    /** Tetrahedral interpolation; writes the result into [out] (size 3). */
    fun sampleLut(lut: Lut3d, r: Float, g: Float, b: Float, out: FloatArray) {
        val n = lut.size
        val s = (n - 1).toFloat()
        val fr = r.coerceIn(0f, 1f) * s
        val fg = g.coerceIn(0f, 1f) * s
        val fb = b.coerceIn(0f, 1f) * s
        val r0 = min(fr.toInt(), n - 2)
        val g0 = min(fg.toInt(), n - 2)
        val b0 = min(fb.toInt(), n - 2)
        val x = fr - r0
        val y = fg - g0
        val z = fb - b0
        val d = lut.data
        fun idx(dr: Int, dg: Int, db: Int) = (((b0 + db) * n + (g0 + dg)) * n + (r0 + dr)) * 3
        val c000 = idx(0, 0, 0)
        val c111 = idx(1, 1, 1)
        val w0: Float; val w1: Float; val w2: Float; val w3: Float
        val c1: Int; val c2: Int
        if (x >= y) {
            if (y >= z) { // x >= y >= z
                c1 = idx(1, 0, 0); c2 = idx(1, 1, 0); w0 = 1 - x; w1 = x - y; w2 = y - z; w3 = z
            } else if (x >= z) { // x >= z > y
                c1 = idx(1, 0, 0); c2 = idx(1, 0, 1); w0 = 1 - x; w1 = x - z; w2 = z - y; w3 = y
            } else { // z > x >= y
                c1 = idx(0, 0, 1); c2 = idx(1, 0, 1); w0 = 1 - z; w1 = z - x; w2 = x - y; w3 = y
            }
        } else {
            if (x >= z) { // y > x >= z
                c1 = idx(0, 1, 0); c2 = idx(1, 1, 0); w0 = 1 - y; w1 = y - x; w2 = x - z; w3 = z
            } else if (y >= z) { // y >= z > x
                c1 = idx(0, 1, 0); c2 = idx(0, 1, 1); w0 = 1 - y; w1 = y - z; w2 = z - x; w3 = x
            } else { // z > y > x
                c1 = idx(0, 0, 1); c2 = idx(0, 1, 1); w0 = 1 - z; w1 = z - y; w2 = y - x; w3 = x
            }
        }
        for (c in 0..2) {
            out[c] = w0 * d[c000 + c] + w1 * d[c1 + c] + w2 * d[c2 + c] + w3 * d[c111 + c]
        }
    }

    fun tone(table: FloatArray, v: Float): Float {
        val pos = v.coerceIn(0f, 1f) * (ToneTable.SIZE - 1)
        val i = min(pos.toInt(), ToneTable.SIZE - 2)
        val f = pos - i
        return table[i] + (table[i + 1] - table[i]) * f
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** Integer hash (lowbias32); identical in C++ and GLSL. */
    fun hash(cx: Int, cy: Int): Int {
        var h = (cx * 73856093) xor (cy * 19349663)
        h = h xor (h ushr 16)
        h *= 0x7feb352d
        h = h xor (h ushr 15)
        h *= 0x846ca68b.toInt()
        h = h xor (h ushr 16)
        return h
    }

    /** Uniform noise in [-0.5, 0.5) for the grain cell containing pixel (x, y). */
    fun grainNoise(x: Int, y: Int, cell: Float): Float {
        val cx = floor(x / cell).toInt()
        val cy = floor(y / cell).toInt()
        return ((hash(cx, cy) ushr 8) / 16777216f) - 0.5f
    }

    fun grainCell(params: LookParams, width: Int, height: Int): Float =
        max(1f, min(width, height) * params.grainSize / 1200f)

    /**
     * Applies the look to one pixel (sRGB values in 0..1) at (x, y) of a
     * [width] x [height] image. Writes into [out].
     */
    fun apply(
        params: LookParams, r: Float, g: Float, b: Float,
        x: Int, y: Int, width: Int, height: Int, out: FloatArray,
    ) {
        sampleLut(params.lut, r, g, b, out)
        val k = params.intensity
        var cr = r + (out[0] - r) * k
        var cg = g + (out[1] - g) * k
        var cb = b + (out[2] - b) * k
        cr += (tone(params.tone, cr) - cr) * k
        cg += (tone(params.tone, cg) - cg) * k
        cb += (tone(params.tone, cb) - cb) * k

        if (params.vignetteAmount > 0f) {
            val nx = (x + 0.5f) / width * 2f - 1f
            val ny = (y + 0.5f) / height * 2f - 1f
            val w2 = width.toFloat() * width
            val h2 = height.toFloat() * height
            val dist = sqrt((nx * nx * w2 + ny * ny * h2) / (w2 + h2))
            val v = 1f - params.vignetteAmount * k * smoothstep(1f - params.vignetteSoftness, 1f, dist)
            cr *= v; cg *= v; cb *= v
        }
        if (params.grainAmount > 0f) {
            val n = grainNoise(x, y, grainCell(params, width, height))
            val l = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb
            val gr = n * params.grainAmount * k * 0.4f * (1f - abs(2f * l - 1f))
            cr += gr; cg += gr; cb += gr
        }
        out[0] = cr.coerceIn(0f, 1f)
        out[1] = cg.coerceIn(0f, 1f)
        out[2] = cb.coerceIn(0f, 1f)
    }

    /** Applies the look in place to ARGB pixels (alpha is kept). */
    fun applyArgb(params: LookParams, pixels: IntArray, width: Int, height: Int) {
        val out = FloatArray(3)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val p = pixels[i]
            apply(
                params, ((p shr 16) and 0xff) / 255f, ((p shr 8) and 0xff) / 255f, (p and 0xff) / 255f,
                x, y, width, height, out,
            )
            pixels[i] = (p and 0xff000000.toInt()) or (quantize(out[0]) shl 16) or
                (quantize(out[1]) shl 8) or quantize(out[2])
        }
    }

    fun quantize(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
}
