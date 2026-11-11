/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.render

import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Params
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Row-major 3x3 matrix acting on column vectors (x, y, 1). */
class Mat3(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)) {
    operator fun times(o: Mat3): Mat3 {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j]
        }
        return Mat3(r)
    }

    /** Projects (x, y); writes the result into [out]. */
    fun map(x: Float, y: Float, out: FloatArray) {
        val w = m[6] * x + m[7] * y + m[8]
        out[0] = (m[0] * x + m[1] * y + m[2]) / w
        out[1] = (m[3] * x + m[4] * y + m[5]) / w
    }

    fun inverse(): Mat3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(abs(det) > 1e-12f) { "Singular matrix" }
        val d = 1f / det
        return Mat3(floatArrayOf(
            c00 * d, (a[2] * a[7] - a[1] * a[8]) * d, (a[1] * a[5] - a[2] * a[4]) * d,
            c01 * d, (a[0] * a[8] - a[2] * a[6]) * d, (a[2] * a[3] - a[0] * a[5]) * d,
            c02 * d, (a[1] * a[6] - a[0] * a[7]) * d, (a[0] * a[4] - a[1] * a[3]) * d,
        ))
    }

    /** Column-major copy for `glUniformMatrix3fv(..., false, ...)`. */
    fun columnMajor() = floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])

    companion object {
        fun translate(x: Float, y: Float) = Mat3(floatArrayOf(1f, 0f, x, 0f, 1f, y, 0f, 0f, 1f))
        fun scale(x: Float, y: Float) = Mat3(floatArrayOf(x, 0f, 0f, 0f, y, 0f, 0f, 0f, 1f))
        fun rotate(rad: Float): Mat3 {
            val c = cos(rad)
            val s = sin(rad)
            return Mat3(floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f))
        }
    }
}

/**
 * Crop, quarter turns, flips, straighten and keystone. Forward order (source to output):
 * flip, quarter turns, keystone, straighten about the centre, crop. The crop rectangle is
 * normalized to the frame after the quarter turns.
 */
object Geometry {
    class Frame(val outWidth: Int, val outHeight: Int, val outputToSource: Mat3)

    /** Frame size after quarter turns. */
    fun turnedSize(width: Int, height: Int, stack: EditStack): Pair<Int, Int> =
        if (quarterTurns(stack) % 2 == 1) height to width else width to height

    fun quarterTurns(stack: EditStack) = stack.effective(Params.CROP_ROT90).roundToInt() and 3

    /**
     * Maps output pixel-normalized coordinates (0..1 over the cropped output) to source
     * normalized coordinates (0..1 over the upright source of [width] x [height]).
     */
    fun frame(stack: EditStack, width: Int, height: Int): Frame {
        val (tw, th) = turnedSize(width, height, stack)
        val l = stack.effective(Params.CROP_LEFT)
        val t = stack.effective(Params.CROP_TOP)
        val r = max(stack.effective(Params.CROP_RIGHT), l + 0.01f)
        val b = max(stack.effective(Params.CROP_BOTTOM), t + 0.01f)
        val outW = max(1, ((r - l) * tw).roundToInt())
        val outH = max(1, ((b - t) * th).roundToInt())
        // Output uv -> turned-frame pixels, centred.
        var m = Mat3.translate(-tw / 2f, -th / 2f) * Mat3.translate(l * tw, t * th) * Mat3.scale((r - l) * tw, (b - t) * th)
        // Undo straighten.
        m = Mat3.rotate(-Math.toRadians(stack.effective(Params.CROP_ANGLE).toDouble()).toFloat()) * m
        // Undo keystone (in frame units normalized by the long side).
        val n = max(tw, th).toFloat()
        val kv = stack.effective(Params.PERSPECTIVE_V) / 100f * 0.5f
        val kh = stack.effective(Params.PERSPECTIVE_H) / 100f * 0.5f
        val keystone = Mat3.scale(n, n) * Mat3(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, kh, kv, 1f)) * Mat3.scale(1f / n, 1f / n)
        m = keystone.inverse() * m
        // Undo quarter turns: turned frame (centred) -> upright source (centred).
        m = Mat3.rotate((-Math.PI / 2 * quarterTurns(stack)).toFloat()) * m
        // Undo flips, then to normalized source coordinates.
        val fx = if (stack.effective(Params.CROP_FLIP_H) >= 0.5f) -1f else 1f
        val fy = if (stack.effective(Params.CROP_FLIP_V) >= 0.5f) -1f else 1f
        m = Mat3.scale(fx, fy) * m
        m = Mat3.scale(1f / width, 1f / height) * Mat3.translate(width / 2f, height / 2f) * m
        return Frame(outW, outH, m)
    }

    /**
     * Shrinks the crop around its centre until no corner of it falls outside the straightened
     * image (auto-expand: the user never sees an empty border). Returns the new stack.
     */
    fun fitCrop(stack: EditStack, width: Int, height: Int): EditStack {
        var s = stack
        repeat(24) {
            if (inside(s, width, height)) return s
            val l = s[Params.CROP_LEFT]; val t = s[Params.CROP_TOP]
            val r = s[Params.CROP_RIGHT]; val b = s[Params.CROP_BOTTOM]
            val cx = (l + r) / 2; val cy = (t + b) / 2
            val k = 0.95f
            s = s.with(Params.CROP_LEFT, cx - (cx - l) * k).with(Params.CROP_RIGHT, cx + (r - cx) * k)
                .with(Params.CROP_TOP, cy - (cy - t) * k).with(Params.CROP_BOTTOM, cy + (b - cy) * k)
        }
        return s
    }

    fun inside(stack: EditStack, width: Int, height: Int): Boolean {
        val f = frame(stack, width, height)
        val p = FloatArray(2)
        for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.5f to 0f, 0.5f to 1f, 0f to 0.5f, 1f to 0.5f)) {
            f.outputToSource.map(u, v, p)
            if (p[0] < -1e-3f || p[0] > 1.001f || p[1] < -1e-3f || p[1] > 1.001f) return false
        }
        return true
    }

    /** Sets a crop of aspect [ratio] (width / height of the output), as large as fits, centred. */
    fun applyAspect(stack: EditStack, width: Int, height: Int, ratio: Float): EditStack {
        val (tw, th) = turnedSize(width, height, stack)
        val base = stack.with(Params.CROP_ASPECT, ratio)
        val target = when {
            ratio == 0f -> return base
            ratio < 0f -> width.toFloat() / height
            else -> ratio
        }
        var w = 1f
        var h = (tw / target) / th
        if (h > 1f) { w = 1f / h; h = 1f }
        val s = base.with(Params.CROP_LEFT, (1 - w) / 2).with(Params.CROP_RIGHT, (1 + w) / 2)
            .with(Params.CROP_TOP, (1 - h) / 2).with(Params.CROP_BOTTOM, (1 + h) / 2)
        return fitCrop(s, width, height)
    }

    /** Source bounding box (normalized) needed to render an output region (normalized). */
    fun sourceBounds(frame: Frame, u0: Float, v0: Float, u1: Float, v1: Float): FloatArray {
        val p = FloatArray(2)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val steps = 4
        for (i in 0..steps) for (j in 0..steps) {
            if (i in 1 until steps && j in 1 until steps) continue
            frame.outputToSource.map(u0 + (u1 - u0) * i / steps, v0 + (v1 - v0) * j / steps, p)
            minX = min(minX, p[0]); maxX = max(maxX, p[0]); minY = min(minY, p[1]); maxY = max(maxY, p[1])
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
