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

class ExposureAidsTest {
    private val all = ProSettings(zebra = true, peaking = true, falseColor = true, histogram = true)

    @Test
    fun zebraThresholds() {
        assertEquals(242, ExposureAids.zebraThresholdLuma(95))
        assertEquals(255, ExposureAids.zebraThresholdLuma(100))
        assertEquals(179, ExposureAids.zebraThresholdLuma(70))
        assertEquals(255, ExposureAids.zebraThresholdLuma(150))
        assertEquals(95, ProSettings().zebraPercent)
        assertEquals(100, ExposureAids.nextZebraLevel(95))
        assertEquals(70, ExposureAids.nextZebraLevel(100))
        assertEquals(90, ExposureAids.nextZebraLevel(85))
    }

    @Test
    fun falseColorBands() {
        val bands = ExposureAids.FALSE_COLOR_BANDS
        assertEquals(0x7F00FF, bands[ExposureAids.falseColorBand(0)].color)
        assertEquals(0x0050FF, bands[ExposureAids.falseColorBand(15)].color)
        assertEquals(-1, ExposureAids.falseColorBand(60))
        // 18% grey in sRGB is about 46% luma: middle grey must land in the green band.
        assertEquals(0x00C000, bands[ExposureAids.falseColorBand(118)].color)
        assertEquals(0xFF80C0, bands[ExposureAids.falseColorBand(166)].color)
        assertEquals(0xFFE000, bands[ExposureAids.falseColorBand(240)].color)
        assertEquals(0xFF0000, bands[ExposureAids.falseColorBand(255)].color)
        assertEquals(0xFF0000, bands[ExposureAids.falseColorBand(300)].color)
        // Bands never overlap.
        bands.zipWithNext().forEach { (a, b) -> assertTrue(a.highPercent <= b.lowPercent) }
    }

    @Test
    fun shaderMirrorsTheBands() {
        val src = ExposureAids.SHADER
        assertEquals(ExposureAids.FALSE_COLOR_BANDS.size, Regex("pc >= ").findAll(src).count())
        assertTrue("uniform shader frame;" in src)
        assertFalse(',' in Regex("""\d,\d""").find(src)?.value.orEmpty()) // locale-independent numbers
    }

    @Test
    fun histogramBinning() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val grey = 0xFF808080.toInt()
        // 4x2: row 0 black white black white, row 1 grey x4.
        val px = intArrayOf(black, white, black, white, grey, grey, grey, grey)
        assertArrayEquals(intArrayOf(2, 0, 4, 2), ExposureAids.histogram(px, 4, step = 1, bins = 4))
        // Step 2 samples (0,0) and (2,0) only: both black.
        assertArrayEquals(intArrayOf(2, 0, 0, 0), ExposureAids.histogram(px, 4, step = 2, bins = 4))
        assertEquals(8, ExposureAids.histogram(px, 4, step = 1).sum())
    }

    @Test(expected = IllegalArgumentException::class)
    fun histogramRejectsBadWidth() {
        ExposureAids.histogram(IntArray(4), 0)
    }

    @Test
    fun gpuSupportByApiLevel() {
        assertEquals(ExposureAids.Unavailable.API_LEVEL, ExposureAids.gpuSupport(32))
        assertEquals(ExposureAids.Unavailable.API_LEVEL, ExposureAids.gpuSupport(26))
        assertNull(ExposureAids.gpuSupport(33))
        assertNull(ExposureAids.gpuSupport(37))
    }

    @Test
    fun activeAidsFollowTheFallback() {
        val ok = ExposureAids.active(all, null)
        assertTrue(ok.zebra && ok.peaking && ok.falseColor && ok.histogram)
        assertFalse(ExposureAids.suppressed(all, null))

        for (reason in listOf(
            ExposureAids.Unavailable.API_LEVEL,
            ExposureAids.Unavailable.SHADER_COMPILE,
            ExposureAids.Unavailable.DRAW,
        )) {
            val a = ExposureAids.active(all, reason)
            assertFalse(a.shader)
            assertTrue("histogram survives $reason", a.histogram)
            assertTrue(ExposureAids.suppressed(all, reason))
        }

        val noFrames = ExposureAids.active(all, ExposureAids.Unavailable.FRAME_COPY)
        assertFalse(noFrames.any)

        // Only the histogram requested: a shader failure needs no notice.
        val histOnly = ProSettings(histogram = true)
        assertFalse(ExposureAids.suppressed(histOnly, ExposureAids.Unavailable.SHADER_COMPILE))
        assertTrue(ExposureAids.suppressed(histOnly, ExposureAids.Unavailable.FRAME_COPY))
        assertFalse(ExposureAids.active(ProSettings(histogram = false), null).any)
    }

    @Test
    fun copyFailuresNeedALongRun() {
        val f = ExposureAids.CopyFailures(limit = 3)
        assertFalse(f.record(false))
        assertFalse(f.record(false))
        assertFalse(f.record(true))
        assertFalse(f.record(false))
        assertFalse(f.record(false))
        assertTrue(f.record(false))
        f.reset()
        assertFalse(f.record(false))
    }
}
