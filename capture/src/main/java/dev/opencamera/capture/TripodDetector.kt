/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import kotlin.math.sqrt

/**
 * Rolling gyroscope statistics. The device counts as on a tripod when the RMS angular velocity
 * stays under [tripodRadPerSec] for at least [tripodHoldNs].
 */
class TripodDetector(
    private val windowNs: Long = 1_000_000_000L,
    private val tripodRadPerSec: Float = 0.008f,
    private val tripodHoldNs: Long = 1_500_000_000L,
) {
    private val times = ArrayDeque<Long>()
    private val energies = ArrayDeque<Float>()
    private var energySum = 0.0
    private var steadySinceNs = -1L
    private var lastNs = -1L

    @Synchronized
    fun add(timestampNs: Long, wx: Float, wy: Float, wz: Float) {
        val e = wx * wx + wy * wy + wz * wz
        times.addLast(timestampNs)
        energies.addLast(e)
        energySum += e
        while (times.isNotEmpty() && timestampNs - times.first() > windowNs) {
            times.removeFirst()
            energySum -= energies.removeFirst()
        }
        lastNs = timestampNs
        if (rmsLocked() < tripodRadPerSec) {
            if (steadySinceNs < 0) steadySinceNs = timestampNs
        } else {
            steadySinceNs = -1L
        }
    }

    @Synchronized
    fun state(): MotionState = MotionState(
        angularRms = rmsLocked(),
        onTripod = steadySinceNs >= 0 && lastNs - steadySinceNs >= tripodHoldNs,
    )

    @Synchronized
    fun reset() {
        times.clear()
        energies.clear()
        energySum = 0.0
        steadySinceNs = -1L
        lastNs = -1L
    }

    private fun rmsLocked(): Float =
        if (energies.isEmpty()) 0f else sqrt((energySum / energies.size).coerceAtLeast(0.0)).toFloat()
}
