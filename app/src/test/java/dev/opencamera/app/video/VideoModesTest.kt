/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import dev.opencamera.pipeline.looks.Lut3d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModesTest {
    private val full = VideoModeCaps(
        canRecord = true, canAnalyze = true, highSpeedFps = 240, maxRegularFps = 60, hasGyro = true,
        hasConcurrentFrontBack = true, openCv = true, hasSegmenter = true,
    )

    @Test
    fun everythingWhenCapable() {
        assertEquals(VideoMode.entries, VideoModes.supported(full))
    }

    @Test
    fun modesHideWithoutTheirCapability() {
        val bare = full.copy(
            highSpeedFps = 0, maxRegularFps = 30, hasGyro = false, hasConcurrentFrontBack = false,
            openCv = false, hasSegmenter = false,
        )
        assertEquals(
            listOf(
                VideoMode.PRO_VIDEO, VideoMode.TIME_LAPSE, VideoMode.SHORT_VIDEO,
                VideoMode.SLOW_SHUTTER, VideoMode.TIME_FREEZE,
            ),
            VideoModes.supported(bare),
        )
        assertTrue(VideoModes.supported(full.copy(canRecord = false, canAnalyze = false)).isEmpty())
    }

    @Test
    fun slowMotionFallsBackToRetimingThenHides() {
        assertEquals(SlowMotionPlan.HighSpeed(240), VideoModes.slowMotionPlan(full))
        assertEquals(SlowMotionPlan.Retimed(60, 2), VideoModes.slowMotionPlan(full.copy(highSpeedFps = 0)))
        assertEquals(
            SlowMotionPlan.Unsupported,
            VideoModes.slowMotionPlan(full.copy(highSpeedFps = 0, maxRegularFps = 30)),
        )
        assertFalse(VideoModes.isSupported(VideoMode.SLOW_MOTION, full.copy(canRecord = false)))
    }

    @Test
    fun recordPlanDropsUnsupportedAndStepsDown() {
        val caps = RecordCaps(listOf("UHD", "FHD"), listOf(30, 60), hdr = true, toneCurve = false,
            halStabilization = true, hevc = true)
        val wanted = VideoSettings(qualityName = "UHD", fps = 60, hevc = true, log = true, hdr = true,
            stabilization = Stabilization.HAL)
        val attempts = RecordPlan.attempts(wanted, caps)
        // Log is unsupported (no tone curve) and asked HDR is dropped because log was requested.
        assertEquals(RecordAttempt("UHD", 60, hdr = false, log = false, halStabilization = true, hevc = true), attempts[0])
        assertEquals(RecordAttempt(null, 30, false, false, false, false), attempts.last())
        assertTrue(attempts.any { it.fps == 30 && !it.halStabilization && it.qualityName == "UHD" })
    }

    @Test
    fun recordPlanUnknownQualityUsesBest() {
        val caps = RecordCaps(listOf("FHD"), listOf(30), false, false, false, false)
        val first = RecordPlan.attempts(VideoSettings(qualityName = "UHD", fps = 120), caps).first()
        assertEquals("FHD", first.qualityName)
        assertEquals(30, first.fps)
    }

    @Test
    fun logCurveInverts() {
        for (i in 0..20) {
            val x = i / 20f
            assertEquals(x, LogProfile.decode(LogProfile.encode(x)), 1e-4f)
        }
        val pts = LogProfile.curvePoints(64)
        assertEquals(128, pts.size)
        assertEquals(1f, pts[126], 0f)
        assertEquals(1f, pts[127], 1e-5f)
        assertTrue(pts[33] > pts[32]) // lifted shadows
    }

    @Test
    fun gradeCubeWithIdentityLookMatchesPlainDecode() {
        val plain = LogProfile.gradeCube(null, 0f, size = 5)
        val withIdentity = LogProfile.gradeCube(Lut3d.identity(9), 1f, size = 5)
        for (r in 0 until 5) for (g in 0 until 5) for (b in 0 until 5) {
            val a = plain[r][g][b]
            val c = withIdentity[r][g][b]
            for (shift in intArrayOf(0, 8, 16)) {
                assertTrue(kotlin.math.abs(((a shr shift) and 0xff) - ((c shr shift) and 0xff)) <= 1)
            }
        }
        assertEquals(0xff000000.toInt(), plain[0][0][0])
        assertEquals(0xffffffff.toInt(), plain[4][4][4])
    }

    @Test
    fun timelineSkipsPauses() {
        val t = RecordingTimeline(startNs = 1_000)
        assertEquals(1_000 + 5_000, t.toElapsedNs(5))
        t.onPause(10_000)
        t.onResume(30_000)
        assertEquals(1_000 + 5_000, t.toElapsedNs(5))
        assertEquals(1_000 + 20_000 + 20_000, t.toElapsedNs(20))
    }
}
