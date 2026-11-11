/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui

import dev.opencamera.app.models.GridMode
import dev.opencamera.app.ui.assist.CompositionHints
import dev.opencamera.app.ui.assist.LevelMath
import dev.opencamera.app.ui.views.GridGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AssistGeometryTest {
    private val g = 9.81f

    @Test
    fun thirdsSplitBothAxes() {
        val l = GridGeometry.lines(GridMode.ON_3, 300f, 600f)
        assertEquals(4, l.size)
        assertEquals(100f, l[0][0], 1e-3f)
        assertEquals(200f, l[1][1], 1e-3f)
        assertEquals(200f, l[2][0], 1e-3f)
        assertEquals(400f, l[3][1], 1e-3f)
    }

    @Test
    fun goldenRatioLines() {
        val l = GridGeometry.lines(GridMode.ON_GOLDEN_RATIO, 1000f, 1000f)
        assertEquals(381.966f, l[0][0], 0.01f)
        assertEquals(618.034f, l[2][0], 0.01f)
    }

    @Test
    fun fourByFourAndDiagonal() {
        assertEquals(6, GridGeometry.lines(GridMode.ON_4, 400f, 400f).size)
        val d = GridGeometry.lines(GridMode.DIAGONAL, 400f, 800f)
        assertTrue(d.any { it.contentEquals(floatArrayOf(0f, 0f, 400f, 800f)) })
        assertTrue(d.any { it.contentEquals(floatArrayOf(400f, 0f, 0f, 800f)) })
    }

    @Test
    fun squareIsCentredAndSquare() {
        val l = GridGeometry.lines(GridMode.SQUARE, 300f, 500f)
        val top = l[0]
        assertEquals(0f, top[0], 1e-3f)
        assertEquals(100f, top[1], 1e-3f)
        assertEquals(300f, top[2], 1e-3f)
        val left = l[3]
        assertEquals(400f, left[1], 1e-3f)
        assertEquals(100f, left[3], 1e-3f)
    }

    @Test
    fun offOrEmptyHasNoLines() {
        assertTrue(GridGeometry.lines(GridMode.OFF, 100f, 100f).isEmpty())
        assertTrue(GridGeometry.lines(GridMode.ON_3, 0f, 100f).isEmpty())
    }

    /** Gravity reading of a phone rotated clockwise by [deg] in the screen plane. */
    private fun upright(deg: Float): LevelMath.Reading {
        val r = Math.toRadians(deg.toDouble())
        return LevelMath.read((-g * sin(r)).toFloat(), (g * cos(r)).toFloat(), 0f)!!
    }

    @Test
    fun horizonInAllOrientations() {
        for (base in listOf(0f, 90f, 180f, -90f)) {
            val r = upright(base + 5f) as LevelMath.Reading.Horizon
            assertEquals("base $base", -5f, r.rollDeg, 0.01f)
            assertFalse(r.level)
            assertTrue((upright(base + 0.5f) as LevelMath.Reading.Horizon).level)
        }
    }

    @Test
    fun bubbleWhenFlat() {
        val flat = LevelMath.read(0f, 0f, g) as LevelMath.Reading.Bubble
        assertTrue(flat.level)
        assertEquals(0f, flat.x, 1e-6f)
        // Right edge lower: the bubble floats left.
        val tilted = LevelMath.read(-g * 0.1f, 0f, g) as LevelMath.Reading.Bubble
        assertTrue(tilted.x < 0f)
        assertFalse(tilted.level)
    }

    @Test
    fun noReadingWithoutGravity() {
        assertNull(LevelMath.read(0f, 0f, 0f))
    }

    @Test
    fun hapticTicksOnceUntilRearmed() {
        val h = LevelMath.Haptic()
        assertTrue(h.shouldTick(LevelMath.Reading.Horizon(0.5f)))
        assertFalse(h.shouldTick(LevelMath.Reading.Horizon(0.2f)))
        assertFalse(h.shouldTick(LevelMath.Reading.Horizon(1.5f)))
        assertFalse(h.shouldTick(LevelMath.Reading.Horizon(0.5f)))
        h.shouldTick(LevelMath.Reading.Horizon(3f))
        assertTrue(h.shouldTick(LevelMath.Reading.Horizon(0.5f)))
    }

    @Test
    fun compositionHints() {
        assertTrue(CompositionHints.compute(null, null, null, null, null, 0.5f).isEmpty())
        val hints = CompositionHints.compute(0.5f, 0.5f, 0.333f, 0.333f, 0.02f, -4f)
        assertEquals(CompositionHints.Hint.Straighten(clockwise = false), hints[0])
        assertTrue(hints[1] is CompositionHints.Hint.Move)
        assertEquals(CompositionHints.Hint.Headroom, hints[2])
        // Close enough to the thirds point: no arrow.
        assertTrue(CompositionHints.compute(0.35f, 0.34f, 0.333f, 0.333f, 0.3f, 0f).isEmpty())
    }
}
