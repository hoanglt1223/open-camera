/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Exposure stops, value formatting and exposure math.
 */
object ExposureMath {
    private val NOMINAL_ISO = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
        1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600,
        32000, 40000, 51200, 64000, 80000, 102400,
    )

    // Third-stop shutter speeds in seconds, as printed on camera dials.
    private val NOMINAL_SHUTTER_S = doubleArrayOf(
        1 / 8000.0, 1 / 6400.0, 1 / 5000.0, 1 / 4000.0, 1 / 3200.0, 1 / 2500.0, 1 / 2000.0,
        1 / 1600.0, 1 / 1250.0, 1 / 1000.0, 1 / 800.0, 1 / 640.0, 1 / 500.0, 1 / 400.0,
        1 / 320.0, 1 / 250.0, 1 / 200.0, 1 / 160.0, 1 / 125.0, 1 / 100.0, 1 / 80.0, 1 / 60.0,
        1 / 50.0, 1 / 40.0, 1 / 30.0, 1 / 25.0, 1 / 20.0, 1 / 15.0, 1 / 13.0, 1 / 10.0,
        1 / 8.0, 1 / 6.0, 1 / 5.0, 1 / 4.0, 0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5,
        3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0, 15.0, 20.0, 25.0, 30.0, 60.0, 120.0, 240.0,
    )

    /** Third-stop ISO values inside [range], always including both ends. */
    fun isoStops(range: IntRange): List<Int> =
        (listOf(range.first) + NOMINAL_ISO.filter { it in range } + range.last).distinct().sorted()

    /** Third-stop shutter speeds inside [rangeNs], always including both ends. */
    fun shutterStops(rangeNs: LongRange): List<Long> =
        (listOf(rangeNs.first) + NOMINAL_SHUTTER_S.map { (it * 1e9).roundToLong() }
            .filter { it in rangeNs } + rangeNs.last).distinct().sorted()

    /** Index of the stop closest to [value] on a log scale. */
    fun <T : Number> nearestIndex(stops: List<T>, value: T): Int =
        stops.indices.minBy { abs(ln(stops[it].toDouble()) - ln(value.toDouble())) }

    fun formatShutter(ns: Long): String {
        val s = ns / 1e9
        return if (s < 0.3) {
            "1/${(1 / s).roundToInt()}"
        } else {
            val tenths = (s * 10).roundToInt()
            if (tenths % 10 == 0) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
        }
    }

    fun formatEv(ev: Float): String {
        val tenths = (ev * 10).roundToInt()
        val sign = if (tenths > 0) "+" else if (tenths < 0) "-" else "±"
        return "$sign${abs(tenths) / 10}.${abs(tenths) % 10}"
    }

    /** Focus distance in metres for a Camera2 diopter value, `null` for infinity. */
    fun dioptersToMeters(diopters: Float): Float? = if (diopters <= 0f) null else 1f / diopters

    /** Exposure value at ISO 100: EV100 = log2(N^2 / t) - log2(ISO / 100). */
    fun ev100(fNumber: Double, exposureNs: Long, iso: Int): Double =
        log2(fNumber * fNumber / (exposureNs / 1e9)) - log2(iso / 100.0)

    /**
     * Exposure offset in stops of a preview frame relative to 18% grey, from the mean of
     * sRGB-encoded luma (0..255). Used as a light meter while exposure is manual.
     */
    fun meterOffset(meanLuma: Double): Double {
        val linear = srgbToLinear(meanLuma / 255.0).coerceAtLeast(1e-4)
        return log2(linear / 0.18)
    }

    fun srgbToLinear(v: Double) =
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/**
 * Colour temperature to Camera2 white balance, following the DNG colour model:
 * ColorMatrix maps XYZ to camera RGB, ForwardMatrix maps white-balanced camera RGB to XYZ D50.
 */
object WhiteBalanceMath {
    /** Row-major 3x3 matrices for two calibration illuminants. */
    data class Calibration(
        val colorMatrix1: DoubleArray,
        val colorMatrix2: DoubleArray,
        val forwardMatrix1: DoubleArray?,
        val forwardMatrix2: DoubleArray?,
        val cct1: Double,
        val cct2: Double,
    )

    /** Linear sRGB from XYZ D50 (Bradford adapted to D65). */
    val XYZ_D50_TO_SRGB = doubleArrayOf(
        3.1338561, -1.6168667, -0.4906146,
        -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427,
    )

    /** CIE 1931 xy chromaticity of the Planckian locus (Kim et al. cubic fit, 1667..25000 K). */
    fun cctToXy(kelvin: Double): Pair<Double, Double> {
        val t = kelvin.coerceIn(1667.0, 25000.0)
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 4000) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val x2 = x * x
        val x3 = x2 * x
        val y = when {
            t <= 2222 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
            t <= 4000 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    fun xyToXyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)

    /** DNG spec interpolation weight of illuminant 1, linear in inverse temperature. */
    fun weight1(kelvin: Double, cct1: Double, cct2: Double): Double {
        if (cct1 == cct2) return 1.0
        return ((1 / kelvin - 1 / cct2) / (1 / cct1 - 1 / cct2)).coerceIn(0.0, 1.0)
    }

    fun lerp(a: DoubleArray, b: DoubleArray, wa: Double) =
        DoubleArray(9) { a[it] * wa + b[it] * (1 - wa) }

    /**
     * Gains (R, G, G, B) that render a neutral surface lit at [kelvin] as grey, normalised so
     * green is 1.
     */
    fun gains(kelvin: Double, calibration: Calibration): FloatArray {
        val w = weight1(kelvin, calibration.cct1, calibration.cct2)
        val cm = lerp(calibration.colorMatrix1, calibration.colorMatrix2, w)
        val (x, y) = cctToXy(kelvin)
        val neutral = Matrix3.mulVec(cm, xyToXyz(x, y))
        val g = neutral[1]
        return floatArrayOf(
            (g / neutral[0]).toFloat(), 1f, 1f, (g / neutral[2]).toFloat(),
        )
    }

    /**
     * Matrix from white-balanced camera RGB to linear sRGB, or `null` when the camera has no
     * forward matrices.
     */
    fun colorTransform(kelvin: Double, calibration: Calibration): DoubleArray? {
        val fm1 = calibration.forwardMatrix1 ?: return null
        val fm2 = calibration.forwardMatrix2 ?: fm1
        val w = weight1(kelvin, calibration.cct1, calibration.cct2)
        val m = Matrix3.mul(XYZ_D50_TO_SRGB, lerp(fm1, fm2, w))
        // Scale so camera white (1,1,1) maps to sRGB white with G = 1, keeping exposure stable.
        val white = Matrix3.mulVec(m, doubleArrayOf(1.0, 1.0, 1.0))
        return DoubleArray(9) { m[it] / white[it / 3] }
    }

    /** Nearest Camera2 AWB preset for a Kelvin value when manual gains are unavailable. */
    fun nearestPreset(kelvin: Int): WbMode = listOf(
        WbMode.INCANDESCENT to 2850,
        WbMode.FLUORESCENT to 4000,
        WbMode.DAYLIGHT to 5500,
        WbMode.CLOUDY to 6500,
        WbMode.SHADE to 7500,
    ).minBy { abs(it.second - kelvin) }.first
}

object Matrix3 {
    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) {
        val r = it / 3
        val c = it % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun mulVec(m: DoubleArray, v: DoubleArray) = DoubleArray(3) {
        m[it * 3] * v[0] + m[it * 3 + 1] * v[1] + m[it * 3 + 2] * v[2]
    }
}

/**
 * Viewfinder assists computed on a downscaled ARGB preview frame.
 */
object FrameAssists {
    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    fun lumaPlane(pixels: IntArray) = IntArray(pixels.size) { luma(pixels[it]) }

    fun histogram(luma: IntArray, bins: Int = 64): IntArray {
        val out = IntArray(bins)
        for (v in luma) out[v * bins shr 8]++
        return out
    }

    /** Sobel gradient magnitude (|gx| + |gy|) above [threshold] marks an in-focus edge. */
    fun peakingMask(luma: IntArray, width: Int, height: Int, threshold: Int): BooleanArray {
        val out = BooleanArray(luma.size)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val tl = luma[i - width - 1]
                val t = luma[i - width]
                val tr = luma[i - width + 1]
                val l = luma[i - 1]
                val r = luma[i + 1]
                val bl = luma[i + width - 1]
                val b = luma[i + width]
                val br = luma[i + width + 1]
                val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                out[i] = abs(gx) + abs(gy) > threshold
            }
        }
        return out
    }

    /** Diagonal stripes over pixels at or above [threshold] luma. */
    fun zebraMask(luma: IntArray, width: Int, threshold: Int, stripe: Int = 4): BooleanArray =
        BooleanArray(luma.size) {
            luma[it] >= threshold && ((it % width + it / width) / stripe) % 2 == 0
        }
}
