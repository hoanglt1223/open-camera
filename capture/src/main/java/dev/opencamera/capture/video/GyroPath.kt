/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture.video

import kotlin.math.cos
import kotlin.math.sin

/**
 * Integrated gyroscope angles (device axes, radians) over time. Small-angle integration per axis
 * is enough for stabilizing hand shake, where the motion between two samples is tiny.
 *
 * Timestamps are nanoseconds in the elapsedRealtime clock (SensorEvent.timestamp, and camera
 * frames when SENSOR_INFO_TIMESTAMP_SOURCE is REALTIME).
 */
class GyroPath(private val capacity: Int = 200 * 60 * 15) {
    private val times = LongArray(capacity)
    private val angles = FloatArray(capacity * 3)
    private var count = 0
    private val acc = FloatArray(3)

    val size get() = count
    val isEmpty get() = count == 0

    @Synchronized
    fun add(timeNs: Long, wx: Float, wy: Float, wz: Float) {
        if (count == capacity) return
        if (count > 0) {
            val dt = (timeNs - times[count - 1]) / 1e9f
            if (dt <= 0f) return
            // A gap (sensor paused) would integrate a stale rate; keep the angle instead.
            if (dt <= 0.5f) {
                acc[0] += wx * dt
                acc[1] += wy * dt
                acc[2] += wz * dt
            }
        }
        times[count] = timeNs
        angles[count * 3] = acc[0]
        angles[count * 3 + 1] = acc[1]
        angles[count * 3 + 2] = acc[2]
        count++
    }

    @Synchronized
    fun clear() {
        count = 0
        acc.fill(0f)
    }

    /** Angles at [timeNs], linearly interpolated and clamped to the recorded span. */
    @Synchronized
    fun angleAt(timeNs: Long, out: FloatArray = FloatArray(3)): FloatArray {
        if (count == 0) return out.also { it.fill(0f) }
        val i = upperBound(timeNs)
        if (i == 0) return copy(0, out)
        if (i >= count) return copy(count - 1, out)
        val t0 = times[i - 1]
        val t1 = times[i]
        val f = ((timeNs - t0).toDouble() / (t1 - t0)).toFloat()
        for (k in 0..2) {
            out[k] = angles[(i - 1) * 3 + k] * (1 - f) + angles[i * 3 + k] * f
        }
        return out
    }

    /** Mean angle over [timeNs - halfWindowNs, timeNs + halfWindowNs] (centered, for post). */
    @Synchronized
    fun smoothedAt(timeNs: Long, halfWindowNs: Long, out: FloatArray = FloatArray(3)): FloatArray {
        if (count == 0) return out.also { it.fill(0f) }
        val from = upperBound(timeNs - halfWindowNs - 1).coerceAtMost(count - 1)
        val to = (upperBound(timeNs + halfWindowNs) - 1).coerceAtLeast(from)
        out.fill(0f)
        for (i in from..to) for (k in 0..2) out[k] += angles[i * 3 + k]
        val n = (to - from + 1).toFloat()
        for (k in 0..2) out[k] /= n
        return out
    }

    /** Correction (smoothed minus raw) to apply at [timeNs]. */
    fun correctionAt(timeNs: Long, halfWindowNs: Long): FloatArray {
        val raw = angleAt(timeNs)
        val smooth = smoothedAt(timeNs, halfWindowNs)
        return floatArrayOf(smooth[0] - raw[0], smooth[1] - raw[1], smooth[2] - raw[2])
    }

    private fun copy(i: Int, out: FloatArray) = out.also {
        it[0] = angles[i * 3]; it[1] = angles[i * 3 + 1]; it[2] = angles[i * 3 + 2]
    }

    private fun upperBound(t: Long): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Image-space correction for a rotation correction in device axes. Frames are upright in the
 * device's natural (portrait) orientation, x right and y down. [frameRotationDeg] rotates the
 * shift when the frame is upright for a rotated device (landscape video).
 */
data class FrameCorrection(val dxPx: Float, val dyPx: Float, val rollDeg: Float) {
    companion object {
        /** Extra zoom that hides the borders: the 10% crop margin. */
        const val CROP_SCALE = 1.1f

        fun from(
            correction: FloatArray,
            focalPx: Float,
            frameRotationDeg: Int = 0,
            mirrored: Boolean = false,
            maxShiftFraction: Float = 0.045f,
            frameLongSidePx: Int = 0,
        ): FrameCorrection {
            var dx = focalPx * correction[1]
            var dy = focalPx * correction[0]
            if (mirrored) dx = -dx
            val r = Math.toRadians(-frameRotationDeg.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            val rx = dx * c - dy * s
            val ry = dx * s + dy * c
            val limit = if (frameLongSidePx > 0) maxShiftFraction * frameLongSidePx else Float.MAX_VALUE
            return FrameCorrection(
                rx.coerceIn(-limit, limit),
                ry.coerceIn(-limit, limit),
                Math.toDegrees(correction[2].toDouble()).toFloat().coerceIn(-5f, 5f),
            )
        }
    }
}

/** Causal smoother for live use (hyperlapse): exponential low-pass of the gyro path. */
class LiveStabilizer(private val alpha: Float = 0.08f) {
    private val smooth = FloatArray(3)
    private var initialized = false

    fun correction(raw: FloatArray): FloatArray {
        if (!initialized) {
            raw.copyInto(smooth)
            initialized = true
        } else {
            for (k in 0..2) smooth[k] += alpha * (raw[k] - smooth[k])
        }
        return floatArrayOf(smooth[0] - raw[0], smooth[1] - raw[1], smooth[2] - raw[2])
    }
}
