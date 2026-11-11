/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import dev.opencamera.ml.tasks.Mask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskStabilizerTest {
    private fun mask(fraction: Float, w: Int = 10, h: Int = 10): Mask {
        val n = (w * h * fraction).toInt()
        return Mask(w, h, FloatArray(w * h) { if (it < n) 1f else 0f })
    }

    @Test
    fun noSubjectMeansNoBlur() {
        val s = MaskStabilizer()
        repeat(5) { assertNull(s.update(null)) }
        repeat(5) { assertNull(s.update(mask(0f))) }
        assertFalse(s.visible)
    }

    @Test
    fun showsOnlyAfterConsecutiveDetections() {
        val s = MaskStabilizer()
        assertNull(s.update(mask(0.3f)))
        assertNotNull(s.update(mask(0.3f)))
        assertTrue(s.visible)
    }

    @Test
    fun singleMissDoesNotBlink() {
        val s = MaskStabilizer()
        s.update(mask(0.3f)); s.update(mask(0.3f))
        assertNotNull(s.update(null))
        assertNotNull(s.update(mask(0.3f)))
        repeat(3) { s.update(null) }
        assertFalse(s.visible)
    }

    @Test
    fun averagesOverFrames() {
        val s = MaskStabilizer(alpha = 0.5f)
        s.update(mask(0.5f))
        val out = s.update(Mask(10, 10, FloatArray(100)))
        // Second frame not yet confirmed (coverage 0 resets hits): nothing shown.
        assertNull(out)
        val s2 = MaskStabilizer(alpha = 0.5f)
        s2.update(Mask(10, 10, FloatArray(100) { 1f }))
        val m = s2.update(Mask(10, 10, FloatArray(100) { 0.6f }))!!
        assertEquals(0.8f, m.data[0], 1e-6f)
    }

    @Test
    fun geometryChangeRestarts() {
        val s = MaskStabilizer()
        s.update(mask(0.3f)); s.update(mask(0.3f))
        val m = s.update(mask(0.3f, 8, 12))!!
        assertEquals(8, m.width)
        assertEquals(12, m.height)
    }
}
