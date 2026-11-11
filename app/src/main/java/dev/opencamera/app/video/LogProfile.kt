/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.hardware.camera2.params.TonemapCurve
import dev.opencamera.pipeline.looks.Lut3d
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Flat "log" video: the HAL contrast curve is replaced with a log curve that keeps highlight
 * and shadow detail, and the grade (log back to display, then the chosen look) is applied in
 * post as a 3D LUT. Our own curve, so it is exactly invertible.
 */
object LogProfile {
    private const val K = 40f
    private val NORM = ln(1f + K)

    fun encode(x: Float) = ln(1f + K * x.coerceIn(0f, 1f)) / NORM
    fun decode(y: Float) = (((1f + K).pow(y.coerceIn(0f, 1f))) - 1f) / K

    /** Interleaved (in, out) pairs for one channel, at most [maxPoints] points. */
    fun curvePoints(maxPoints: Int): FloatArray {
        val n = maxPoints.coerceIn(2, 64)
        return FloatArray(n * 2) { i ->
            val x = (i / 2) / (n - 1f)
            if (i % 2 == 0) x else encode(x)
        }
    }

    fun tonemapCurve(maxPoints: Int): TonemapCurve {
        val p = curvePoints(maxPoints)
        return TonemapCurve(p, p, p)
    }

    /** LUT cube indexed [r][g][b] holding RGB ints, for Media3's SingleColorLut. */
    fun gradeCube(look: Lut3d?, intensity: Float, size: Int = 33): Array<Array<IntArray>> {
        val rgb = FloatArray(3)
        val graded = FloatArray(3)
        return Array(size) { r ->
            Array(size) { g ->
                IntArray(size) { b ->
                    rgb[0] = decode(r / (size - 1f))
                    rgb[1] = decode(g / (size - 1f))
                    rgb[2] = decode(b / (size - 1f))
                    // The decoded value is linear light; display it with the sRGB-like 1/2.2.
                    for (k in 0..2) rgb[k] = rgb[k].pow(1f / 2.2f)
                    if (look != null) {
                        sample(look, rgb[0], rgb[1], rgb[2], graded)
                        for (k in 0..2) rgb[k] += (graded[k] - rgb[k]) * intensity
                    }
                    (0xff shl 24) or (to8(rgb[0]) shl 16) or (to8(rgb[1]) shl 8) or to8(rgb[2])
                }
            }
        }
    }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    /** Trilinear lookup in a `.cube`-ordered LUT (red fastest). */
    fun sample(lut: Lut3d, r: Float, g: Float, b: Float, out: FloatArray) {
        val n = lut.size
        val max = n - 1f
        val fr = r.coerceIn(0f, 1f) * max
        val fg = g.coerceIn(0f, 1f) * max
        val fb = b.coerceIn(0f, 1f) * max
        val r0 = fr.toInt().coerceAtMost(n - 2)
        val g0 = fg.toInt().coerceAtMost(n - 2)
        val b0 = fb.toInt().coerceAtMost(n - 2)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        for (k in 0..2) {
            fun v(ri: Int, gi: Int, bi: Int) = lut.data[(((b0 + bi) * n + (g0 + gi)) * n + (r0 + ri)) * 3 + k]
            val c00 = v(0, 0, 0) * (1 - dr) + v(1, 0, 0) * dr
            val c10 = v(0, 1, 0) * (1 - dr) + v(1, 1, 0) * dr
            val c01 = v(0, 0, 1) * (1 - dr) + v(1, 0, 1) * dr
            val c11 = v(0, 1, 1) * (1 - dr) + v(1, 1, 1) * dr
            val c0 = c00 * (1 - dg) + c10 * dg
            val c1 = c01 * (1 - dg) + c11 * dg
            out[k] = c0 * (1 - db) + c1 * db
        }
    }
}
