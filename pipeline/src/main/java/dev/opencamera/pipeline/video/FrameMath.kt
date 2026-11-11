/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.video

/** BT.601 limited-range ARGB to YUV, the layout video encoders expect for SDR. */
object Yuv {
    fun y(r: Int, g: Int, b: Int) = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
    fun u(r: Int, g: Int, b: Int) = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
    fun v(r: Int, g: Int, b: Int) = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

    /** Planar I420 for an even-sized ARGB frame. Chroma takes the top-left pixel of each 2x2. */
    fun argbToI420(argb: IntArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        val uBase = width * height
        val vBase = uBase + width * height / 4
        for (row in 0 until height) {
            for (col in 0 until width) {
                val p = argb[row * width + col]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                out[row * width + col] = y(r, g, b).toByte()
                if (row and 1 == 0 && col and 1 == 0) {
                    val ci = (row / 2) * (width / 2) + col / 2
                    out[uBase + ci] = u(r, g, b).toByte()
                    out[vBase + ci] = v(r, g, b).toByte()
                }
            }
        }
        return out
    }
}

/**
 * Slow shutter by frame accumulation: AVERAGE blurs motion (silky water), LIGHTEN keeps the
 * brightest value of every pixel (light trails).
 */
class FrameAccumulator(val width: Int, val height: Int, val mode: Mode) {
    enum class Mode { AVERAGE, LIGHTEN }

    private val sum = IntArray(width * height * 3)
    var frames = 0
        private set

    fun add(argb: IntArray) {
        require(argb.size == width * height) { "frame size mismatch" }
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            val o = i * 3
            when (mode) {
                Mode.AVERAGE -> {
                    sum[o] += r; sum[o + 1] += g; sum[o + 2] += b
                }
                Mode.LIGHTEN -> {
                    if (r > sum[o]) sum[o] = r
                    if (g > sum[o + 1]) sum[o + 1] = g
                    if (b > sum[o + 2]) sum[o + 2] = b
                }
            }
        }
        frames++
    }

    fun result(): IntArray {
        val n = if (mode == Mode.AVERAGE) frames.coerceAtLeast(1) else 1
        return IntArray(width * height) { i ->
            val o = i * 3
            (0xff shl 24) or ((sum[o] / n) shl 16) or ((sum[o + 1] / n) shl 8) or (sum[o + 2] / n)
        }
    }
}

/**
 * Time freeze: a line sweeps left to right; columns behind it keep the pixels they had when the
 * line passed, columns ahead stay live.
 */
class TimeFreezeCompositor(val width: Int, val height: Int) {
    private val output = IntArray(width * height)
    private var started = false

    /** [progress] in 0..1 is the line position; returns the composed frame (shared buffer). */
    fun compose(frame: IntArray, progress: Float): IntArray {
        val line = if (!started) 0 else (progress.coerceIn(0f, 1f) * width).toInt()
        started = true
        for (row in 0 until height) {
            val base = row * width
            System.arraycopy(frame, base + line, output, base + line, width - line)
        }
        return output
    }
}
