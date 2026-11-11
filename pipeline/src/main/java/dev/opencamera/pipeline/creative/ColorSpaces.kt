/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import kotlin.math.cbrt
import kotlin.math.pow

/** sRGB <-> linear <-> Oklab (transfer working space) and CIELAB D65 (for Delta E). */
object ColorSpaces {
    fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    }

    /** sRGB (0..1) to Oklab; writes L, a, b into [out] at [offset]. */
    fun srgbToOklab(r: Float, g: Float, b: Float, out: FloatArray, offset: Int = 0) {
        val lr = srgbToLinear(r)
        val lg = srgbToLinear(g)
        val lb = srgbToLinear(b)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        out[offset] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        out[offset + 1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        out[offset + 2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    /** Oklab to sRGB (0..1, clipped); writes r, g, b into [out]. */
    fun oklabToSrgb(L: Float, a: Float, b: Float, out: FloatArray) {
        val l = L + 0.3963377774f * a + 0.2158037573f * b
        val m = L - 0.1055613458f * a - 0.0638541728f * b
        val s = L - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        out[0] = linearToSrgb(4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3)
        out[1] = linearToSrgb(-1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3)
        out[2] = linearToSrgb(-0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3)
    }

    /** sRGB (0..1) to CIELAB (D65); writes L*, a*, b* into [out] at [offset]. */
    fun srgbToLab(r: Float, g: Float, b: Float, out: FloatArray, offset: Int = 0) {
        val lr = srgbToLinear(r)
        val lg = srgbToLinear(g)
        val lb = srgbToLinear(b)
        val x = (0.4124564f * lr + 0.3575761f * lg + 0.1804375f * lb) / 0.95047f
        val y = 0.2126729f * lr + 0.7151522f * lg + 0.0721750f * lb
        val z = (0.0193339f * lr + 0.1191920f * lg + 0.9503041f * lb) / 1.08883f
        val fx = labF(x)
        val fy = labF(y)
        val fz = labF(z)
        out[offset] = 116f * fy - 16f
        out[offset + 1] = 500f * (fx - fy)
        out[offset + 2] = 200f * (fy - fz)
    }

    private fun labF(t: Float): Float = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
}
