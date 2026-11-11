/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SportsPickerTest {
    private val w = 160
    private val h = 120

    private val background = Random(11).let { r -> IntArray(w * h) { 60 + r.nextInt(128) } }
    private val subject = Random(12).let { r -> IntArray(24 * 24) { 200 - r.nextInt(128) / 2 } }

    /** Textured background with a textured 24x24 subject at (sx, sy); optional box blur. */
    private fun frame(sx: Int, sy: Int, blurSubject: Int, blurBackground: Int, rng: Random): LumaFrame {
        val img = background.copyOf()
        for (y in 0 until 24) for (x in 0 until 24) {
            if (sy + y in 0 until h && sx + x in 0 until w) img[(sy + y) * w + sx + x] = subject[y * 24 + x]
        }
        val out = blur(img, blurBackground)
        val subj = blur(img, blurSubject)
        for (y in 0 until 24) for (x in 0 until 24) {
            val i = (sy + y) * w + sx + x
            if (sy + y in 0 until h && sx + x in 0 until w) out[i] = subj[i]
        }
        return LumaFrame(w, h, ByteArray(w * h) { (out[it] + rng.nextInt(-2, 3)).coerceIn(0, 255).toByte() })
    }

    private fun blur(img: IntArray, r: Int): IntArray {
        if (r == 0) return img.copyOf()
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0
            var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                s += img[yy * w + xx]
                n++
            }
            s / n
        }
    }

    @Test
    fun motionLocatorFindsTheMovingSubject() {
        val rng = Random(1)
        val frames = (0 until 9).map { frame(10 + 12 * it, 40, 0, 0, rng) }
        val box = MotionSubjectLocator.locate(frames, null)
        assertNotNull(box)
        // Middle frame subject at (58, 40) .. (82, 64).
        assertTrue("box $box", abs(box!!.cx - 70) <= 6 && abs(box.cy - 52) <= 6)
    }

    @Test
    fun staticSceneHasNoMovingSubject() {
        val rng = Random(2)
        val frames = (0 until 6).map { frame(60, 40, 0, 0, rng) }
        assertNull(MotionSubjectLocator.locate(frames, null))
    }

    @Test
    fun trackerFollowsTheSubject() {
        val rng = Random(3)
        val frames = (0 until 9).map { frame(10 + 12 * it, 40 + it, 0, 0, rng) }
        val boxes = TemplateTracker.track(frames, 4, Box(58, 44, 24, 24))
        boxes.forEachIndexed { i, b ->
            assertTrue("frame $i box $b", abs(b.x - (10 + 12 * i)) <= 2 && abs(b.y - (40 + i)) <= 2)
        }
    }

    @Test
    fun picksFramesWhereTheSubjectIsSharp() {
        val rng = Random(4)
        // Subject sharp (camera panning, background soft) in frames 2, 5, 7; elsewhere the
        // subject is motion blurred and the background sharp, which wins a whole-frame ranking.
        val sharpSubject = setOf(2, 5, 7)
        val frames = (0 until 10).map {
            if (it in sharpSubject) frame(10 + 12 * it, 40, 0, 1, rng) else frame(10 + 12 * it, 40, 2, 0, rng)
        }
        val picks = SportsPicker.pick(frames, 3)
        assertEquals(sharpSubject, picks.toSet())
        val whole = SportsPicker.pick(frames, 3, locator = { _, _ -> null })
        assertTrue("whole-frame ranking ${whole.toList()}", whole.toSet() != sharpSubject)
    }

    @Test
    fun hintSelectsTheBoxAroundThePoint() {
        val frames = listOf(LumaFrame(w, h, ByteArray(w * h)))
        val box = MotionSubjectLocator.locate(frames, 0.99f to 0.01f)!!
        assertTrue(box.x + box.w <= w && box.y >= 0)
    }

    @Test
    fun brokenLocatorFallsBackToWholeFrame() {
        val rng = Random(5)
        val frames = (0 until 4).map { frame(10, 10, 0, 0, rng) }
        val picks = SportsPicker.pick(frames, 2, locator = { _, _ -> error("model failed") })
        assertEquals(2, picks.size)
    }
}
