/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MotionTakeTest {
    private fun checker(w: Int, h: Int, cell: Int) = ByteArray(w * h) { i ->
        if (((i % w) / cell + (i / w) / cell) % 2 == 0) 0 else 0xff.toByte()
    }

    /** 3x3 box blur, the stand-in for a shaken frame. */
    private fun blur(src: ByteArray, w: Int, h: Int) = ByteArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var s = 0
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until w && yy in 0 until h) { s += src[yy * w + xx].toInt() and 0xff; n++ }
        }
        (s / n).toByte()
    }

    @Test
    fun laplacianVarianceRanksSharpAboveBlurredAndFlat() {
        val w = 64
        val h = 48
        val sharp = checker(w, h, 4)
        val soft = blur(blur(sharp, w, h), w, h)
        val flat = ByteArray(w * h) { 120 }
        val s = BestTake.sharpness(sharp, w, h)
        val b = BestTake.sharpness(soft, w, h)
        assertTrue("sharp $s > blurred $b", s > b * 2)
        assertEquals(0.0, BestTake.sharpness(flat, w, h), 1e-9)
        // Row stride padding is skipped.
        val padded = ByteArray((w + 16) * h).also { p -> for (r in 0 until h) sharp.copyInto(p, r * (w + 16), r * w, r * w + w) }
        assertEquals(s, BestTake.sharpness(padded, w, h, stride = w + 16), 1e-9)
    }

    @Test
    fun pickOffersOnlyAClearlyBetterTake() {
        val shutter = 5
        // Shutter frame is the sharpest: nothing to offer.
        assertNull(BestTake.pick(listOf(BestTake.Candidate(5, 100.0), BestTake.Candidate(2, 90.0)), shutter))
        // Slightly sharper is not enough.
        assertNull(BestTake.pick(listOf(BestTake.Candidate(5, 100.0), BestTake.Candidate(2, 110.0)), shutter))
        assertEquals(2, BestTake.pick(listOf(BestTake.Candidate(5, 100.0), BestTake.Candidate(2, 130.0)), shutter)?.index)
        // Closed eyes on the shutter frame make a slightly softer open-eyed take win.
        assertEquals(
            3,
            BestTake.pick(listOf(BestTake.Candidate(5, 100.0, eyes = 0f), BestTake.Candidate(3, 90.0, eyes = 1f)), shutter)?.index,
        )
        // ... and closed eyes on the sharper take keep the shutter frame.
        assertNull(BestTake.pick(listOf(BestTake.Candidate(5, 100.0, eyes = 1f), BestTake.Candidate(3, 140.0, eyes = 0f)), shutter))
        // Shutter frame missing from the candidates: no offer.
        assertNull(BestTake.pick(listOf(BestTake.Candidate(1, 500.0)), shutter))
        assertEquals(0f, BestTake.eyeOpenness(0.05f))
        assertEquals(1f, BestTake.eyeOpenness(0.3f))
        assertEquals(listOf(2, 0), BestTake.sharpest(doubleArrayOf(5.0, 1.0, 9.0), 2))
    }

    @Test
    fun argbToNv21MatchesBt601() {
        val w = 4
        val h = 2
        val argb = IntArray(w * h) { if (it % w < 2) 0xff000000.toInt() else 0xffff0000.toInt() }
        val out = ByteArray(w * h * 3 / 2)
        MotionPhotoPolicy.argbToNv21(argb, w, h, out)
        assertEquals(0, out[0].toInt() and 0xff)
        assertEquals(77, out[2].toInt() and 0xff)
        // Chroma of black: neutral; of red: V high, U low (V comes first in NV21).
        assertEquals(128, out[8].toInt() and 0xff)
        assertEquals(128, out[9].toInt() and 0xff)
        assertEquals(255, out[10].toInt() and 0xff)
        assertTrue((out[11].toInt() and 0xff) < 100)
    }

    @Test
    fun fallbackReasons() {
        assertEquals(MotionFailure.LOW_MEMORY, MotionPhotoPolicy.precheck(123L, 40, lowMemory = true))
        assertEquals(MotionFailure.NO_STREAM, MotionPhotoPolicy.precheck(0L, 0, lowMemory = false))
        assertEquals(MotionFailure.TOO_FEW_FRAMES, MotionPhotoPolicy.precheck(123L, MotionPhotoPolicy.MIN_FRAMES - 1, false))
        assertNull(MotionPhotoPolicy.precheck(123L, MotionPhotoPolicy.MIN_FRAMES, false))
    }

    @Test
    fun ringCapacityHonoursTheMemoryBudget() {
        val budget = 32L shl 20
        assertEquals(48, MotionPhotoPolicy.ringCapacity(768 * 576 * 3 / 2, 48, budget))
        assertEquals(18, MotionPhotoPolicy.ringCapacity(1280 * 960 * 3 / 2, 48, budget))
        assertEquals(1, MotionPhotoPolicy.ringCapacity(64 shl 20, 48, budget))
        assertEquals(48, MotionPhotoPolicy.ringCapacity(0, 48, budget))
    }

    @Test
    fun closestFrameToTheShutter() {
        val ts = (0 until 60).map { it * 66_000_000L + Random(1).nextLong(1000) }
        val shutter = ts[30]
        assertEquals(30, MotionPhotoPolicy.closest(ts, shutter + 10_000_000L))
        assertEquals(-1, MotionPhotoPolicy.closest(emptyList(), shutter))
    }
}
