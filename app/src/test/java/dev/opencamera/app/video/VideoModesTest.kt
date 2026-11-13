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
import kotlin.math.roundToInt

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
    fun exportedCubeRoundTripsAndMatchesBakedGrade() {
        val lut = LogProfile.gradeLut(null, 0f, size = 9)
        val parsed = dev.opencamera.pipeline.looks.CubeParser.parse(
            dev.opencamera.pipeline.looks.CubeWriter.write(lut, LogOutput.cubeTitle("VID_1")),
        )
        assertEquals(9, parsed.size)
        for (i in lut.data.indices) assertEquals(lut.data[i], parsed.data[i], 1e-5f)
        // The baked copy uses the same table: red index 8, green 0, blue 0 in both orders.
        val cube = LogProfile.toColorCube(lut)
        val o = (0 * 9 + 0) * 9 * 3 + 8 * 3
        assertEquals((lut.data[o] * 255f).roundToInt(), (cube[8][0][0] shr 16) and 0xff)
        assertEquals(0f, lut.data[1], 1e-6f)
    }

    @Test
    fun logOutputsDependOnLogAndBake() {
        assertTrue(LogOutput.bakes(log = true, bakeSetting = true))
        assertFalse(LogOutput.bakes(log = true, bakeSetting = false))
        // A log request the camera downgraded to a plain recording never bakes a grade.
        assertFalse(LogOutput.bakes(log = false, bakeSetting = true))
        assertEquals("VID_20261113_101010_123.cube", LogProfile.sidecarName("VID_20261113_101010_123"))
        assertEquals("VID_1.cube", LogProfile.sidecarName("VID_1.mp4"))
    }

    @Test
    fun logSettingsDefaultToPreviewAndBake() {
        val s = VideoSettings()
        assertTrue(s.logPreview)
        assertTrue(s.logBake)
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
