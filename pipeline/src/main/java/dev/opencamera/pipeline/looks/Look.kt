/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Parsed `look.json`. Colour lives in the LUT; everything else is applied at runtime. */
data class LookDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val lutFile: String,
    val intensity: Float = 1f,
    val curve: List<Pair<Float, Float>> = listOf(0f to 0f, 1f to 1f),
    val highlightRolloff: Float = 0f,
    val grainAmount: Float = 0f,
    val grainSize: Float = 1f,
    val vignetteAmount: Float = 0f,
    val vignetteSoftness: Float = 0.5f,
) {
    companion object {
        fun parse(json: String): LookDescriptor {
            val o = JSONObject(json)
            val curve = o.optJSONArray("curve")?.let { arr ->
                List(arr.length()) { i ->
                    val p = arr.getJSONArray(i)
                    p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
                }
            } ?: listOf(0f to 0f, 1f to 1f)
            require(curve.size >= 2) { "curve needs at least two points" }
            require(curve.zipWithNext().all { (a, b) -> b.first > a.first }) { "curve x must increase" }
            val grain = o.optJSONObject("grain")
            val vignette = o.optJSONObject("vignette")
            return LookDescriptor(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description"),
                lutFile = o.optString("lut", "lut.cube"),
                intensity = o.optDouble("intensity", 1.0).toFloat().coerceIn(0f, 1f),
                curve = curve,
                highlightRolloff = o.optDouble("highlightRolloff", 0.0).toFloat().coerceIn(0f, 1f),
                grainAmount = grain?.optDouble("amount", 0.0)?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                grainSize = grain?.optDouble("size", 1.0)?.toFloat()?.coerceIn(0.25f, 4f) ?: 1f,
                vignetteAmount = vignette?.optDouble("amount", 0.0)?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                vignetteSoftness = vignette?.optDouble("softness", 0.5)?.toFloat()?.coerceIn(0.05f, 1f)
                    ?: 0.5f,
            )
        }
    }
}

/**
 * Everything a renderer needs. The GPU preview shader, the native still renderer
 * and [LookMath] all consume exactly these values, so preview and photo match.
 */
class LookParams(
    val lut: Lut3d,
    val tone: FloatArray,
    val intensity: Float,
    val grainAmount: Float,
    val grainSize: Float,
    val vignetteAmount: Float,
    val vignetteSoftness: Float,
) {
    init {
        require(tone.size == ToneTable.SIZE)
    }

    companion object {
        fun of(descriptor: LookDescriptor, lut: Lut3d, intensity: Float = descriptor.intensity) = LookParams(
            lut = lut,
            tone = ToneTable.build(descriptor.curve, descriptor.highlightRolloff),
            intensity = intensity.coerceIn(0f, 1f),
            grainAmount = descriptor.grainAmount,
            grainSize = descriptor.grainSize,
            vignetteAmount = descriptor.vignetteAmount,
            vignetteSoftness = descriptor.vignetteSoftness,
        )
    }
}

/** 1D tone table: monotone cubic through the curve points, then a highlight shoulder. */
object ToneTable {
    const val SIZE = 256

    fun build(curve: List<Pair<Float, Float>>, rolloff: Float): FloatArray {
        val xs = curve.map { it.first }
        val ys = curve.map { it.second }
        val n = xs.size
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        // Fritsch-Carlson tangents keep the curve monotone between points.
        val m = FloatArray(n) { i ->
            when (i) {
                0 -> d[0]
                n - 1 -> d[n - 2]
                else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            }
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f; m[i + 1] = 0f
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
            }
        }
        val knee = 0.7f
        val k = rolloff * 0.5f
        return FloatArray(SIZE) { idx ->
            val x = idx / (SIZE - 1f)
            var seg = 0
            while (seg < n - 2 && x > xs[seg + 1]) seg++
            val y = if (x <= xs[0]) ys[0] else if (x >= xs[n - 1]) ys[n - 1] else {
                val h = xs[seg + 1] - xs[seg]
                val t = (x - xs[seg]) / h
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * m[seg] +
                    (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * m[seg + 1]
            }
            val shoulder = if (y > knee) {
                val t = (y - knee) / (1f - knee)
                knee + (1f - knee) * (t - k * t * t / 2f)
            } else y
            min(1f, max(0f, shoulder))
        }
    }
}
