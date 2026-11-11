/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroPathTest {
    private val ms = 1_000_000L

    @Test
    fun integratesConstantRate() {
        val path = GyroPath()
        for (i in 0..200) path.add(i * 5 * ms, 0f, 1f, 0f)
        assertEquals(1f, path.angleAt(1000 * ms)[1], 1e-3f)
        assertEquals(0.5f, path.angleAt(500 * ms)[1], 1e-3f)
    }

    @Test
    fun clampsOutsideRecordedSpan() {
        val path = GyroPath()
        assertEquals(0f, path.angleAt(123)[0], 0f)
        path.add(10 * ms, 1f, 0f, 0f)
        path.add(20 * ms, 1f, 0f, 0f)
        assertEquals(0f, path.angleAt(0)[0], 1e-6f)
        assertEquals(0.01f, path.angleAt(99 * ms)[0], 1e-5f)
    }

    @Test
    fun ignoresGapsAndBackwardsTime() {
        val path = GyroPath()
        path.add(0, 0f, 1f, 0f)
        path.add(2000 * ms, 0f, 1f, 0f) // 2 s gap: not integrated
        path.add(1000 * ms, 0f, 1f, 0f) // backwards: dropped
        assertEquals(2, path.size)
        assertEquals(0f, path.angleAt(2000 * ms)[1], 1e-6f)
    }

    @Test
    fun correctionCancelsShakeAroundSteadyPath() {
        val path = GyroPath()
        // Alternating shake: the angle oscillates around zero.
        for (i in 0..400) path.add(i * 5 * ms, 0f, if ((i / 10) % 2 == 0) 0.4f else -0.4f, 0f)
        val t = 1000 * ms
        val raw = path.angleAt(t)[1]
        val c = path.correctionAt(t, 400 * ms)[1]
        assertEquals(path.smoothedAt(t, 400 * ms)[1], raw + c, 1e-5f)
        assertTrue(kotlin.math.abs(raw + c) < 0.03f)
    }

    @Test
    fun frameCorrectionMapsAxesAndClamps() {
        val c = FrameCorrection.from(floatArrayOf(0.01f, 0.02f, 0f), 1000f)
        assertEquals(20f, c.dxPx, 1e-4f)
        assertEquals(10f, c.dyPx, 1e-4f)
        val mirrored = FrameCorrection.from(floatArrayOf(0f, 0.02f, 0f), 1000f, mirrored = true)
        assertEquals(-20f, mirrored.dxPx, 1e-4f)
        val rotated = FrameCorrection.from(floatArrayOf(0f, 0.02f, 0f), 1000f, frameRotationDeg = 90)
        assertEquals(0f, rotated.dxPx, 1e-3f)
        assertEquals(20f, kotlin.math.abs(rotated.dyPx), 1e-3f)
        val clamped = FrameCorrection.from(floatArrayOf(0f, 1f, 1f), 1000f, frameLongSidePx = 1000)
        assertEquals(45f, clamped.dxPx, 1e-3f)
        assertEquals(5f, clamped.rollDeg, 1e-3f)
    }

    @Test
    fun liveStabilizerStartsAtZeroAndLags() {
        val s = LiveStabilizer(alpha = 0.5f)
        assertEquals(0f, s.correction(floatArrayOf(1f, 1f, 1f))[0], 0f)
        val c = s.correction(floatArrayOf(2f, 2f, 2f))
        assertEquals(-0.5f, c[0], 1e-6f)
    }
}
