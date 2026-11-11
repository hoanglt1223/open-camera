/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture.video

import kotlin.math.abs

/**
 * Gyro-guided panorama sweep: tells when the device has turned far enough for the next frame
 * and whether it drifts off the horizon. Angles in degrees; yaw is the rotation about the
 * device y axis (portrait hold, horizontal sweep).
 */
class SweepGuide(
    private val stepDeg: Float = 15f,
    val maxSweepDeg: Float = 180f,
    private val maxPitchDeg: Float = 6f,
) {
    enum class Advice { HOLD, CAPTURE, TOO_HIGH, TOO_LOW, DONE }

    private var lastTimeNs = 0L
    var yawDeg = 0f
        private set
    var pitchDeg = 0f
        private set
    private var lastCaptureYaw = 0f
    private var direction = 0
    var captures = 0
        private set

    /** Total turn since the first frame, in the sweep direction. */
    val sweptDeg get() = abs(yawDeg)

    fun reset() {
        lastTimeNs = 0L; yawDeg = 0f; pitchDeg = 0f; lastCaptureYaw = 0f; direction = 0; captures = 0
    }

    fun onGyro(timeNs: Long, wx: Float, wy: Float) {
        if (lastTimeNs != 0L) {
            val dt = (timeNs - lastTimeNs) / 1e9f
            if (dt in 0f..0.5f) {
                yawDeg += Math.toDegrees((wy * dt).toDouble()).toFloat()
                pitchDeg += Math.toDegrees((wx * dt).toDouble()).toFloat()
            }
        }
        lastTimeNs = timeNs
    }

    /** Advice for the current pose; call [onCaptured] after taking the frame it asked for. */
    fun advice(): Advice {
        if (sweptDeg >= maxSweepDeg) return Advice.DONE
        if (pitchDeg > maxPitchDeg) return Advice.TOO_HIGH
        if (pitchDeg < -maxPitchDeg) return Advice.TOO_LOW
        if (captures == 0) return Advice.CAPTURE
        val moved = yawDeg - lastCaptureYaw
        if (direction == 0 && abs(moved) >= stepDeg) return Advice.CAPTURE
        if (direction != 0 && moved * direction >= stepDeg) return Advice.CAPTURE
        return Advice.HOLD
    }

    fun onCaptured() {
        if (captures >= 1 && direction == 0) direction = if (yawDeg >= lastCaptureYaw) 1 else -1
        lastCaptureYaw = yawDeg
        captures++
    }
}
