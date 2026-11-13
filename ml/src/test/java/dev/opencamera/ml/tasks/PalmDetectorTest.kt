/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmDetectorTest {
    private val n = PalmDetector.ANCHORS.size / 2

    @Test
    fun anchorLayoutMatchesTheModelOutput() {
        assertEquals(2016, n)
    }

    @Test
    fun bestAnchorMapsOutOfTheLetterbox() {
        val reg = FloatArray(n * 18)
        val cls = FloatArray(n) { -10f }
        // Anchor 0 sits at the top-left cell centre (1/48, 1/48) of the square; shift it to the
        // square's centre (96 px) and give it a 48 px wide box.
        reg[0] = 96f - 192f / 48f
        reg[1] = 96f - 192f / 48f
        reg[2] = 48f
        cls[0] = 4f
        // A 4:3 landscape frame fills the full width and the middle 3/4 of the square's height.
        val palm = PalmDetector.decodeBest(reg, cls, 400, 300)!!
        assertEquals(0.982f, palm.score, 1e-3f)
        assertEquals(0.5f, palm.cx, 1e-4f)
        assertEquals(0.5f, palm.cy, 1e-4f)
        assertEquals(0.25f, palm.width, 1e-4f)
        assertTrue(PalmDetector.decodeBest(reg, FloatArray(n) { -10f }, 400, 300)!!.score < 0.01f)
    }

    @Test
    fun badOutputsAreRejected() {
        assertNull(PalmDetector.decodeBest(FloatArray(10), FloatArray(10), 400, 300))
        assertNull(PalmDetector.decodeBest(FloatArray(n * 18), FloatArray(n), 0, 300))
    }
}