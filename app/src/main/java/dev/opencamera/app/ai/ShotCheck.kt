/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import kotlin.math.max

/**
 * Post-capture check of a saved photo: clearly blurred, or someone's eyes closed. Thresholds are
 * conservative (a warning should be right nearly every time). Sharpness is the Laplacian variance
 * of the sharpest tiles (90th percentile of an 8x8 grid) at [ANALYSIS_SIDE] px, so a sharp subject
 * in front of a soft background still counts as sharp. On the test corpus sharp photos score 147
 * and up and a 1.5 px Gaussian blur brings most under 25; dim indoor shots on gauguin score 40-65,
 * so softness there is only flagged when strong.
 */object ShotCheck {
    enum class Verdict { OK, BLURRY, EYES_CLOSED }

    const val ANALYSIS_SIDE = 1024
    private const val BLURRY_BELOW = 25.0
    private const val MIN_CONTRAST = 300.0
    private const val CLOSED_BELOW = 0.25f
    private const val MIN_FACE_WIDTH = 0.06f

    /** A face seen in the photo: least-open eye (0..1) and box width over image width. */
    data class Face(val eyes: Float, val relativeWidth: Float)

    /**
     * [sharpness] is the Laplacian variance and [contrast] the luma variance of the downscaled
     * photo. [faces] is null when faces were not analysed. Closed eyes win over blur: they are
     * the more specific hint.
     */
    fun verdict(sharpness: Double, contrast: Double, faces: List<Face>?): Verdict {
        val closed = faces.orEmpty().any { it.relativeWidth >= MIN_FACE_WIDTH && it.eyes < CLOSED_BELOW }
        return when {
            closed -> Verdict.EYES_CLOSED
            // Flat scenes (a wall, the sky) have little detail to judge and are left alone.
            contrast >= MIN_CONTRAST && sharpness < BLURRY_BELOW -> Verdict.BLURRY
            else -> Verdict.OK
        }
    }

    /** 90th percentile of per-tile Laplacian variance over a [grid] x [grid] split of luma [y]. */
    fun tileSharpness(y: ByteArray, width: Int, height: Int, grid: Int = 8): Double {
        if (width < grid * 3 || height < grid * 3 || y.size < width * height) return 0.0
        val values = DoubleArray(grid * grid)
        for (ty in 0 until grid) for (tx in 0 until grid) {
            val x0 = max(1, tx * width / grid)
            val x1 = minOf(width - 1, (tx + 1) * width / grid)
            val y0 = max(1, ty * height / grid)
            val y1 = minOf(height - 1, (ty + 1) * height / grid)
            var n = 0
            var sum = 0.0
            var sq = 0.0
            for (r in y0 until y1) {
                val row = r * width
                for (col in x0 until x1) {
                    val i = row + col
                    val lap = (y[i - 1].toInt() and 0xff) + (y[i + 1].toInt() and 0xff) +
                        (y[i - width].toInt() and 0xff) + (y[i + width].toInt() and 0xff) - 4 * (y[i].toInt() and 0xff)
                    sum += lap
                    sq += lap.toDouble() * lap
                    n++
                }
            }
            values[ty * grid + tx] = if (n == 0) 0.0 else sq / n - (sum / n) * (sum / n)
        }
        values.sort()
        return values[(values.size * 9) / 10]
    }

    /** Variance of 8-bit luma [y] ([width] x [height]). */
    fun contrast(y: ByteArray, width: Int, height: Int): Double {
        val n = width * height
        if (n <= 0 || y.size < n) return 0.0
        var sum = 0.0
        var sq = 0.0
        for (i in 0 until n) {
            val v = (y[i].toInt() and 0xff).toDouble()
            sum += v
            sq += v * v
        }
        val mean = sum / n
        return sq / n - mean * mean
    }
}
