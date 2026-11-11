/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TripodDetectorTest {
    private val stepNs = 5_000_000L  // 200 Hz

    @Test
    fun steadyGyroBecomesTripodAfterHoldTime() {
        val d = TripodDetector()
        var t = 0L
        repeat(200) { d.add(t, 0.001f, -0.002f, 0.001f); t += stepNs }  // 1 s
        assertFalse(d.state().onTripod)
        repeat(200) { d.add(t, 0.001f, -0.002f, 0.001f); t += stepNs }  // 2 s
        assertTrue(d.state().onTripod)
    }

    @Test
    fun handTremorIsNotTripod() {
        val d = TripodDetector()
        var t = 0L
        repeat(600) { i ->
            val w = 0.05f * sin(i * 0.3f)
            d.add(t, w, -w, 0.5f * w)
            t += stepNs
        }
        val s = d.state()
        assertFalse(s.onTripod)
        assertTrue(s.angularRms > 0.02f)
    }

    @Test
    fun bumpResetsTripod() {
        val d = TripodDetector()
        var t = 0L
        repeat(400) { d.add(t, 0f, 0f, 0f); t += stepNs }
        assertTrue(d.state().onTripod)
        repeat(20) { d.add(t, 0.4f, 0.1f, 0f); t += stepNs }
        assertFalse(d.state().onTripod)
    }
}
