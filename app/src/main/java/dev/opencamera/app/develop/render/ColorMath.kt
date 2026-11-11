/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.render

import kotlin.math.pow

/** Colour helpers shared by the uniforms, the CPU kernel, Auto and RAW develop. */
object ColorMath {
    fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    fun linearToSrgb(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    val SRGB_TO_XYZ = Mat3(floatArrayOf(
        0.4124564f, 0.3575761f, 0.1804375f,
        0.2126729f, 0.7151522f, 0.0721750f,
        0.0193339f, 0.1191920f, 0.9503041f,
    ))

    val P3_TO_XYZ = Mat3(floatArrayOf(
        0.4865709f, 0.2656677f, 0.1982173f,
        0.2289746f, 0.6917385f, 0.0792869f,
        0.0f, 0.0451134f, 1.0439444f,
    ))

    private val BRADFORD = Mat3(floatArrayOf(
        0.8951f, 0.2664f, -0.1614f,
        -0.7502f, 1.7135f, 0.0367f,
        0.0389f, -0.0685f, 1.0296f,
    ))

    /** CIE xy of a Planckian radiator (Kang et al. 2002), valid for 1667..25000 K. */
    fun planckXy(kelvin: Float): FloatArray {
        val t = kelvin.coerceIn(1667f, 25000f).toDouble()
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 4000) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    /** Scene illuminant assumed by the temperature slider (-1..1): positive warms the photo. */
    fun sliderKelvin(temp: Float): Float = (REFERENCE_K * 2.0.pow(temp * 1.2).toFloat()).coerceIn(1667f, 25000f)

    /**
     * Linear-RGB matrix that adapts a photo lit by the slider's illuminant to the reference
     * white (Bradford). [tint] -1..1, positive adds magenta. Identity at (0, 0).
     */
    fun whiteBalance(temp: Float, tint: Float, wide: Boolean = false): Mat3 {
        val src = planckXy(sliderKelvin(temp))
        src[1] += tint * 0.025f
        val dst = planckXy(REFERENCE_K)
        val toXyz = if (wide) P3_TO_XYZ else SRGB_TO_XYZ
        val lmsSrc = lms(src)
        val lmsDst = lms(dst)
        val gain = Mat3(floatArrayOf(lmsDst[0] / lmsSrc[0], 0f, 0f, 0f, lmsDst[1] / lmsSrc[1], 0f, 0f, 0f, lmsDst[2] / lmsSrc[2]))
        return toXyz.inverse() * BRADFORD.inverse() * gain * BRADFORD * toXyz
    }

    private fun lms(xy: FloatArray): FloatArray {
        val bx = xy[0] / xy[1]
        val bz = (1f - xy[0] - xy[1]) / xy[1]
        val m = BRADFORD.m
        return floatArrayOf(
            m[0] * bx + m[1] + m[2] * bz,
            m[3] * bx + m[4] + m[5] * bz,
            m[6] * bx + m[7] + m[8] * bz,
        )
    }

    /** Hexcone hue in degrees (0..360) of an RGB triple; 0 for greys. */
    fun hue(r: Float, g: Float, b: Float): Float {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (d <= 1e-6f) return 0f
        val h = when (mx) {
            r -> ((g - b) / d).let { if (it < 0) it + 6f else it }
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60f
    }

    /** Fully saturated RGB for a hue in degrees. */
    fun hueRgb(hueDeg: Float, out: FloatArray) {
        val h = ((hueDeg % 360f) + 360f) % 360f / 60f
        val x = 1f - kotlin.math.abs(h % 2f - 1f)
        when (h.toInt()) {
            0 -> { out[0] = 1f; out[1] = x; out[2] = 0f }
            1 -> { out[0] = x; out[1] = 1f; out[2] = 0f }
            2 -> { out[0] = 0f; out[1] = 1f; out[2] = x }
            3 -> { out[0] = 0f; out[1] = x; out[2] = 1f }
            4 -> { out[0] = x; out[1] = 0f; out[2] = 1f }
            else -> { out[0] = 1f; out[1] = 0f; out[2] = x }
        }
    }

    const val REFERENCE_K = 6504f
}
