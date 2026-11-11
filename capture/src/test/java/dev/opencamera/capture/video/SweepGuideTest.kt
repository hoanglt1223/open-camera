/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture.video

import org.junit.Assert.assertEquals
import org.junit.Test

class SweepGuideTest {
    private val ms = 1_000_000L

    /** Turns at [degPerSec] about y for [millis], 5 ms per sample. */
    private fun SweepGuide.turn(startMs: Long, millis: Long, degPerSec: Float, pitchDegPerSec: Float = 0f): Long {
        var t = startMs
        while (t <= startMs + millis) {
            onGyro(t * ms, Math.toRadians(pitchDegPerSec.toDouble()).toFloat(), Math.toRadians(degPerSec.toDouble()).toFloat())
            t += 5
        }
        return t
    }

    @Test
    fun firstFrameThenEveryStep() {
        val g = SweepGuide(stepDeg = 15f)
        assertEquals(SweepGuide.Advice.CAPTURE, g.advice())
        g.onCaptured()
        var t = g.turn(0, 500, -20f)
        assertEquals(SweepGuide.Advice.HOLD, g.advice())
        t = g.turn(t, 300, -20f)
        assertEquals(SweepGuide.Advice.CAPTURE, g.advice())
        g.onCaptured()
        // Turning back does not trigger frames once the direction is known.
        g.turn(t, 1000, 20f)
        assertEquals(SweepGuide.Advice.HOLD, g.advice())
    }

    @Test
    fun warnsOnPitchAndFinishes() {
        val g = SweepGuide(stepDeg = 15f, maxSweepDeg = 30f, maxPitchDeg = 6f)
        g.onCaptured()
        var t = g.turn(0, 1000, 0f, pitchDegPerSec = 10f)
        assertEquals(SweepGuide.Advice.TOO_HIGH, g.advice())
        t = g.turn(t, 2000, 0f, pitchDegPerSec = -10f)
        assertEquals(SweepGuide.Advice.TOO_LOW, g.advice())
        t = g.turn(t, 1000, 0f, pitchDegPerSec = 10f)
        g.turn(t, 2000, 20f)
        assertEquals(SweepGuide.Advice.DONE, g.advice())
    }
}
