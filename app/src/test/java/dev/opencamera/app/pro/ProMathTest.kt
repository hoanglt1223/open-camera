/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProMathTest {
    private val ranges = ProRanges(
        iso = 50..6400,
        exposureNs = 10_000L..32_000_000_000L,
        minFocusDiopters = 10f,
        ev = -12..12,
    )

    @Test
    fun clampKeepsValuesInsideDeviceRanges() {
        val s = ProSettings(
            iso = 25, exposureNs = 64_000_000_000L, focusDiopters = 20f, evIndex = 30,
            kelvin = 50000,
        ).clampTo(ranges)
        assertEquals(50, s.iso)
        assertEquals(32_000_000_000L, s.exposureNs)
        assertEquals(10f, s.focusDiopters!!, 0f)
        assertEquals(ProRanges.KELVIN_MAX, s.kelvin)
        // EV compensation has no effect with manual exposure.
        assertEquals(0, s.evIndex)
    }

    @Test
    fun clampDropsManualControlsTheDeviceLacks() {
        val s = ProSettings(iso = 100, exposureNs = 1_000_000L, focusDiopters = 2f, evIndex = -20)
            .clampTo(ProRanges(iso = null, exposureNs = null, minFocusDiopters = 0f, ev = -6..6))
        assertNull(s.iso)
        assertNull(s.exposureNs)
        assertNull(s.focusDiopters)
        assertEquals(-6, s.evIndex)
    }

    @Test
    fun clampTreatsHalfManualExposureAsAuto() {
        val s = ProSettings(iso = 400).clampTo(ranges)
        assertFalse(s.manualExposure)
        assertNull(s.iso)
    }

    @Test
    fun stopsCoverDeviceRangeIncludingEnds() {
        val iso = ExposureMath.isoStops(55..6000)
        assertEquals(55, iso.first())
        assertEquals(6000, iso.last())
        assertTrue(100 in iso && 1600 in iso)

        val shutter = ExposureMath.shutterStops(ranges.exposureNs!!)
        assertEquals(10_000L, shutter.first())
        assertEquals(32_000_000_000L, shutter.last())
        assertTrue(1_000_000L in shutter) // 1/1000 s
        assertTrue(1_000_000_000L in shutter) // 1 s
        assertEquals(shutter.sorted(), shutter)
    }

    @Test
    fun nearestIndexUsesLogScale() {
        val stops = listOf(100, 200, 400, 800)
        assertEquals(1, ExposureMath.nearestIndex(stops, 260))
        assertEquals(2, ExposureMath.nearestIndex(stops, 300))
    }

    @Test
    fun formatsShutterAndEv() {
        assertEquals("1/1000", ExposureMath.formatShutter(1_000_000L))
        assertEquals("1/60", ExposureMath.formatShutter(16_666_667L))
        assertEquals("1s", ExposureMath.formatShutter(1_000_000_000L))
        assertEquals("2.5s", ExposureMath.formatShutter(2_500_000_000L))
        assertEquals("+0.7", ExposureMath.formatEv(0.6667f))
        assertEquals("-1.0", ExposureMath.formatEv(-1f))
        assertEquals("\u00B10.0", ExposureMath.formatEv(0f))
    }

    @Test
    fun exposureValueMath() {
        // Sunny 16: f/16, 1/100 s, ISO 100 is about EV 14.6.
        assertEquals(14.64, ExposureMath.ev100(16.0, 10_000_000L, 100), 0.01)
        // Doubling ISO lowers EV100 for the same settings by one stop.
        assertEquals(
            ExposureMath.ev100(1.8, 1_000_000L, 100) - 1,
            ExposureMath.ev100(1.8, 1_000_000L, 200), 1e-9,
        )
        // Mid grey reads 0, one stop brighter reads about +1.
        val grey = 255 * 0.4614 // sRGB encoding of 18% linear
        assertEquals(0.0, ExposureMath.meterOffset(grey), 0.02)
        assertEquals(1.0, ExposureMath.meterOffset(255 * 0.6342), 0.03)
        assertEquals(2.0f, ExposureMath.dioptersToMeters(0.5f)!!, 1e-6f)
        assertNull(ExposureMath.dioptersToMeters(0f))
    }

    @Test
    fun cctToXyMatchesStandardIlluminants() {
        val (x65, y65) = WhiteBalanceMath.cctToXy(6504.0)
        assertEquals(0.3135, x65, 0.003) // Planckian point near D65
        assertEquals(0.3237, y65, 0.003)
        val (xa, ya) = WhiteBalanceMath.cctToXy(2856.0)
        assertEquals(0.4476, xa, 0.002) // CIE illuminant A
        assertEquals(0.4074, ya, 0.002)
    }

    @Test
    fun gainsWithIdentityCalibrationFollowWhitePoint() {
        // With XYZ == camera RGB the neutral is the white point XYZ itself.
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val cal = WhiteBalanceMath.Calibration(identity, identity, identity, identity, 2856.0, 6504.0)
        val (x, y) = WhiteBalanceMath.cctToXy(5000.0)
        val xyz = WhiteBalanceMath.xyToXyz(x, y)
        val g = WhiteBalanceMath.gains(5000.0, cal)
        assertEquals(1 / xyz[0], g[0].toDouble(), 1e-5)
        assertEquals(1f, g[1], 0f)
        assertEquals(1f, g[2], 0f)
        assertEquals(1 / xyz[2], g[3].toDouble(), 1e-5)
    }

    @Test
    fun warmerLightNeedsMoreBlueGain() {
        // Rough sRGB-like sensor: XYZ -> camera RGB.
        val cm = doubleArrayOf(
            3.24, -1.54, -0.50,
            -0.97, 1.88, 0.04,
            0.06, -0.20, 1.06,
        )
        val cal = WhiteBalanceMath.Calibration(cm, cm, null, null, 2856.0, 6504.0)
        val tungsten = WhiteBalanceMath.gains(3200.0, cal)
        val daylight = WhiteBalanceMath.gains(6500.0, cal)
        assertTrue(tungsten[3] > daylight[3])
        assertTrue(tungsten[0] < daylight[0])
        assertNull(WhiteBalanceMath.colorTransform(5000.0, cal))
    }

    @Test
    fun colorTransformMapsCameraWhiteToSrgbWhite() {
        val d50ToCamera = doubleArrayOf(
            0.4361, 0.3851, 0.1431,
            0.2225, 0.7169, 0.0606,
            0.0139, 0.0971, 0.7141,
        )
        val cal = WhiteBalanceMath.Calibration(
            d50ToCamera, d50ToCamera, d50ToCamera, d50ToCamera, 2856.0, 6504.0,
        )
        val m = WhiteBalanceMath.colorTransform(4000.0, cal)!!
        val white = Matrix3.mulVec(m, doubleArrayOf(1.0, 1.0, 1.0))
        white.forEach { assertEquals(1.0, it, 1e-9) }
    }

    @Test
    fun interpolationWeightIsLinearInMired() {
        assertEquals(1.0, WhiteBalanceMath.weight1(2856.0, 2856.0, 6504.0), 1e-9)
        assertEquals(0.0, WhiteBalanceMath.weight1(6504.0, 2856.0, 6504.0), 1e-9)
        assertEquals(0.0, WhiteBalanceMath.weight1(9000.0, 2856.0, 6504.0), 1e-9)
        val mid = 2 / (1 / 2856.0 + 1 / 6504.0)
        assertEquals(0.5, WhiteBalanceMath.weight1(mid, 2856.0, 6504.0), 1e-9)
    }

    @Test
    fun nearestPresetForKelvin() {
        assertEquals(WbMode.INCANDESCENT, WhiteBalanceMath.nearestPreset(3000))
        assertEquals(WbMode.DAYLIGHT, WhiteBalanceMath.nearestPreset(5200))
        assertEquals(WbMode.SHADE, WhiteBalanceMath.nearestPreset(9000))
    }

    @Test
    fun histogramPeakingAndZebra() {
        val w = 6
        val h = 4
        // Left half black, right half white: a vertical edge at x = 3.
        val luma = IntArray(w * h) { if (it % w >= 3) 255 else 0 }
        val hist = FrameAssists.histogram(luma, 4)
        assertArrayEquals(intArrayOf(12, 0, 0, 12), hist)

        val peaks = FrameAssists.peakingMask(luma, w, h, 200)
        assertTrue(peaks[1 * w + 2] && peaks[1 * w + 3])
        assertFalse(peaks[1 * w + 1])
        assertFalse(peaks[0]) // border pixels are skipped

        val zebra = FrameAssists.zebraMask(luma, w, 250, stripe = 1)
        assertFalse(zebra[0])
        assertTrue(zebra[4] != zebra[5]) // alternating stripes on highlights

        assertEquals(255, FrameAssists.luma(0xFFFFFFFF.toInt()))
        assertEquals(0, FrameAssists.luma(0xFF000000.toInt()))
    }
}
