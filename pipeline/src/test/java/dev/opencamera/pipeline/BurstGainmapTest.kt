/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class BurstGainmapTest {
    private fun plane(vararg v: Float) = LumaPlane(v.size, 1, v)

    @Test
    fun downsampleAveragesBlocksOfLuma() {
        val w = 4
        val h = 2
        val nv21 = ByteArray(w * h * 3 / 2)
        byteArrayOf(0, 2, 200.toByte(), 255.toByte(), 4, 6, 250.toByte(), 255.toByte()).copyInto(nv21)
        val p = BurstGainmap.downsampleLuma(nv21, w, h, 2)
        assertEquals(2, p.width)
        assertEquals(1, p.height)
        assertEquals(3f, p.px[0], 1e-4f)
        assertEquals(240f, p.px[1], 1e-4f)
    }

    @Test
    fun highlightsGetHeadroomShadowsStaySdr() {
        // Clipped highlight kept by tone mapping; shadow lifted by tone mapping.
        val r = BurstGainmap.ratios(plane(255f, 40f), plane(250f, 120f), toneEvStep = 1f)
        val expected = 2f / BurstGainmap.toLinear(250f)
        assertEquals(expected, r[0], 1e-3f)
        assertEquals(1f, r[1], 0f)
    }

    @Test
    fun ratiosAreCappedAndDarkSdrIsSafe() {
        val r = BurstGainmap.ratios(plane(255f, 0f), plane(0f, 0f), toneEvStep = 5f)
        assertEquals(2f.pow(BurstGainmap.MAX_STOPS), r[0], 1e-4f)
        assertEquals(1f, r[1], 0f)
    }

    @Test
    fun encodeIsLogarithmicOverRange() {
        val g = BurstGainmap.encode(floatArrayOf(1f, 2f, 4f), 3, 1)!!
        assertEquals(4f, g.maxRatio, 0f)
        assertArrayEquals(byteArrayOf(0, 128.toByte(), 255.toByte()), g.codes)
    }

    @Test
    fun noUsefulHeadroomGivesNoGainmap() {
        assertNull(BurstGainmap.encode(floatArrayOf(1f, 1.05f), 2, 1))
    }

    @Test
    fun buildNeedsToneBaseAndToneStep() {
        val w = 8
        val h = 4
        val nv21 = ByteArray(w * h * 3 / 2) { if (it < w * h) 250.toByte() else 128.toByte() }
        val base = LumaPlane(w / 4, h / 4, FloatArray(2) { 255f })
        val withBase = MergedImage(w, h, nv21, 0, frameCount = 3, toneBase = base)
        assertNotNull(BurstGainmap.build(withBase, 1f))
        assertNull(BurstGainmap.build(withBase, 0f))
        assertNull(BurstGainmap.build(MergedImage(w, h, nv21, 0, frameCount = 3), 1f))
    }

    @Test
    fun failuresFallBackToPlainJpeg() {
        val sdr = byteArrayOf(1, 2, 3)
        var errors = 0
        assertSame(sdr, BurstGainmap.withGainmapOr(sdr, { errors++ }) { error("encoder broke") })
        assertSame(sdr, BurstGainmap.withGainmapOr(sdr, { errors++ }) { throw OutOfMemoryError() })
        assertSame(sdr, BurstGainmap.withGainmapOr(sdr, { errors++ }) { null })
        assertEquals(2, errors)
        val uhdr = byteArrayOf(9)
        assertSame(uhdr, BurstGainmap.withGainmapOr(sdr, { errors++ }) { uhdr })
        assertTrue(errors == 2)
    }

}
