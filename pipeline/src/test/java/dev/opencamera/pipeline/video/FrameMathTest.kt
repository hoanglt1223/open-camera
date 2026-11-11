/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.video

import dev.opencamera.pipeline.document.QuadMath
import dev.opencamera.pipeline.panorama.PanoramaMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMathTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun yuvLimitedRange() {
        assertEquals(16, Yuv.y(0, 0, 0))
        assertEquals(235, Yuv.y(255, 255, 255))
        assertEquals(128, Yuv.u(128, 128, 128))
        assertEquals(128, Yuv.v(128, 128, 128))
        assertTrue(Yuv.v(255, 0, 0) > 200)
    }

    @Test
    fun i420Layout() {
        val frame = IntArray(4 * 2) { rgb(255, 255, 255) }
        frame[0] = rgb(0, 0, 0)
        val out = Yuv.argbToI420(frame, 4, 2)
        assertEquals(12, out.size)
        assertEquals(16, out[0].toInt() and 0xff)
        assertEquals(235, out[1].toInt() and 0xff)
        // Two chroma samples per plane, from the top-left pixel of each 2x2 block.
        assertEquals(128, out[8].toInt() and 0xff)
        assertEquals(128, out[10].toInt() and 0xff)
    }

    @Test
    fun accumulatorAverageAndLighten() {
        val avg = FrameAccumulator(1, 1, FrameAccumulator.Mode.AVERAGE)
        avg.add(intArrayOf(rgb(100, 0, 200)))
        avg.add(intArrayOf(rgb(200, 100, 0)))
        assertEquals(rgb(150, 50, 100), avg.result()[0])

        val light = FrameAccumulator(1, 1, FrameAccumulator.Mode.LIGHTEN)
        light.add(intArrayOf(rgb(100, 0, 200)))
        light.add(intArrayOf(rgb(200, 100, 0)))
        assertEquals(rgb(200, 100, 200), light.result()[0])
        assertEquals(2, light.frames)
    }

    @Test
    fun timeFreezeKeepsColumnsBehindTheLine() {
        val tf = TimeFreezeCompositor(4, 1)
        tf.compose(intArrayOf(1, 1, 1, 1), 0f)
        val out = tf.compose(intArrayOf(2, 2, 2, 2), 0.5f)
        assertArrayEquals(intArrayOf(1, 1, 2, 2), out)
        val end = tf.compose(intArrayOf(3, 3, 3, 3), 1f)
        assertArrayEquals(intArrayOf(1, 1, 2, 2), end)
    }

    @Test
    fun quadOrderAndSize() {
        // Shuffled corners of a 100 x 50 rectangle.
        val q = QuadMath.order(floatArrayOf(100f, 50f, 0f, 0f, 0f, 50f, 100f, 0f))
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 0f, 100f, 50f, 0f, 50f), q, 0f)
        assertEquals(100 to 50, QuadMath.targetSize(q))
        assertEquals(5000f, QuadMath.area(q), 1e-3f)
    }

    @Test
    fun panoramaGeometry() {
        assertEquals(Math.toRadians(10.0).toFloat() * 1000f, PanoramaMath.expectedShift(-10f, 1000f), 1e-3f)
        assertEquals(0, PanoramaMath.cylinderCropRows(10, 1000, 1e6f))
        assertTrue(PanoramaMath.cylinderCropRows(1000, 1000, 800f) > 0)
        assertTrue(PanoramaMath.isReversed(listOf(0f, 15f, 30f)))
        assertFalse(PanoramaMath.isReversed(listOf(0f, -15f)))
        assertEquals(95f, PanoramaMath.refine(100f, 5.0, 0.3, 1000), 1e-4f)
        assertEquals(100f, PanoramaMath.refine(100f, 5.0, 0.01, 1000), 1e-4f)
        assertEquals(100f, PanoramaMath.refine(100f, 400.0, 0.9, 1000), 1e-4f)
    }

    @Test
    fun retimeFactor() {
        assertEquals(2, VideoRetimer.factorFor(60))
        assertEquals(4, VideoRetimer.factorFor(120))
        assertEquals(0, VideoRetimer.factorFor(30))
    }
}
