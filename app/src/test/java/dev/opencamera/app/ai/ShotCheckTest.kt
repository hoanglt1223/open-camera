/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import dev.opencamera.app.ai.ShotCheck.Face
import dev.opencamera.app.ai.ShotCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShotCheckTest {
    @Test
    fun verdictRules() {
        assertEquals(Verdict.OK, ShotCheck.verdict(500.0, 3000.0, null))
        assertEquals(Verdict.BLURRY, ShotCheck.verdict(8.0, 3000.0, null))
        // A flat scene with little detail is never called blurred.
        assertEquals(Verdict.OK, ShotCheck.verdict(8.0, 150.0, null))
        assertEquals(Verdict.EYES_CLOSED, ShotCheck.verdict(500.0, 3000.0, listOf(Face(0.9f, 0.2f), Face(0.05f, 0.15f))))
        // Closed eyes win over blur; tiny background faces are ignored.
        assertEquals(Verdict.EYES_CLOSED, ShotCheck.verdict(8.0, 3000.0, listOf(Face(0.0f, 0.2f))))
        assertEquals(Verdict.OK, ShotCheck.verdict(500.0, 3000.0, listOf(Face(0.0f, 0.02f))))
    }

    @Test
    fun boxBlurDropsSharpnessUnderTheThreshold() {
        val w = 512
        val h = 384
        val rnd = Random(3)
        // Blocks of random grey: plenty of edges, like a textured scene.
        val blocks = IntArray((w / 16) * (h / 16)) { rnd.nextInt(256) }
        val sharp = ByteArray(w * h) { i -> blocks[(i / w) / 16 * (w / 16) + (i % w) / 16].toByte() }
        val blurred = boxBlur(boxBlur(sharp, w, h, 4), w, h, 4)
        val sSharp = ShotCheck.tileSharpness(sharp, w, h)
        val sBlur = ShotCheck.tileSharpness(blurred, w, h)
        assertTrue("sharp $sSharp", sSharp > 1000)
        assertEquals(Verdict.OK, ShotCheck.verdict(sSharp, ShotCheck.contrast(sharp, w, h), null))
        assertEquals("blur $sBlur", Verdict.BLURRY, ShotCheck.verdict(sBlur, ShotCheck.contrast(blurred, w, h), null))
    }

    @Test
    fun oneSharpRegionIsEnough() {
        // Left quarter sharp blocks, the rest flat grey: like a subject on a soft background.
        val w = 512
        val h = 384
        val rnd = Random(5)
        val y = ByteArray(w * h) { i -> if ((i % w) < w / 4) (if (((i % w) / 8 + (i / w) / 8) % 2 == 0) 20 else 230).toByte() else rnd.nextInt(120, 124).toByte() }
        assertTrue(ShotCheck.tileSharpness(y, w, h) > 1000)
        assertEquals(0.0, ShotCheck.tileSharpness(ByteArray(10), 4, 2), 0.0)
    }

    @Test
    fun contrastOfConstantImageIsZero() {
        assertEquals(0.0, ShotCheck.contrast(ByteArray(100) { 77 }, 10, 10), 1e-9)
        assertEquals(0.0, ShotCheck.contrast(ByteArray(3), 10, 10), 1e-9)
    }

    private fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    sum += src[yy * w + xx].toInt() and 0xff
                    n++
                }
            }
            out[y * w + x] = (sum / n).toByte()
        }
        return out
    }
}
