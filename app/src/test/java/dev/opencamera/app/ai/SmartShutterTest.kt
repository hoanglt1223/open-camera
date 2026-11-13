/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import dev.opencamera.app.ai.SmartShutter.FaceState
import dev.opencamera.app.ai.SmartShutter.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShutterTest {
    private val happy = FaceState(eyes = 0.9f, smile = 0.8f)
    private val blinking = FaceState(eyes = 0.1f, smile = 0.8f)
    private val serious = FaceState(eyes = 0.9f, smile = 0.1f)

    @Test
    fun palmNeedsTwoFramesInARow() {
        val s = SmartShutter()
        assertEquals(Trigger.NONE, s.onFrame(0.9f, null))
        assertEquals(Trigger.NONE, s.onFrame(0.2f, null))
        assertEquals(Trigger.NONE, s.onFrame(0.9f, null))
        assertEquals(Trigger.PALM, s.onFrame(0.8f, null))
        // Fires once, then starts counting again.
        assertEquals(Trigger.NONE, s.onFrame(0.8f, null))
    }

    @Test
    fun everyFaceMustSmileWithOpenEyes() {
        val s = SmartShutter()
        repeat(4) { assertEquals(Trigger.NONE, s.onFrame(null, listOf(happy, blinking))) }
        repeat(4) { assertEquals(Trigger.NONE, s.onFrame(null, listOf(happy, serious))) }
        assertEquals(Trigger.NONE, s.onFrame(null, listOf(happy, happy)))
        assertEquals(Trigger.SMILE, s.onFrame(null, listOf(happy, happy)))
    }

    @Test
    fun timerModesStoreDistinctValues() {
        // The preference persists seconds; two modes with one value could never be told apart.
        val modes = dev.opencamera.app.models.TimerMode.entries
        assertEquals(modes.size, modes.map { it.seconds }.toSet().size)
    }

    @Test
    fun noFacesOrNoModelsNeverFire() {
        val s = SmartShutter()
        repeat(10) { assertEquals(Trigger.NONE, s.onFrame(null, emptyList())) }
        repeat(10) { assertEquals(Trigger.NONE, s.onFrame(null, null)) }
    }

    /** A 478-point mesh with only the points the measures read placed; the rest at the origin. */
    private fun mesh(mouthWidth: Float, eyeGap: Float, cornerLift: Float): FloatArray {
        val xy = FloatArray(478 * 2)
        fun put(i: Int, x: Float, y: Float) { xy[i * 2] = x; xy[i * 2 + 1] = y }
        put(234, 0f, 50f); put(454, 100f, 50f) // face width 100
        put(10, 50f, 0f); put(152, 50f, 120f) // face height 120
        put(61, 50f - mouthWidth / 2, 90f - cornerLift); put(291, 50f + mouthWidth / 2, 90f - cornerLift)
        put(13, 50f, 89f); put(14, 50f, 91f)
        for ((eye, cx) in listOf(intArrayOf(33, 133, 159, 145) to 30f, intArrayOf(362, 263, 386, 374) to 70f)) {
            put(eye[0], cx - 10, 40f); put(eye[1], cx + 10, 40f)
            put(eye[2], cx, 40f - eyeGap / 2); put(eye[3], cx, 40f + eyeGap / 2)
        }
        return xy
    }

    @Test
    fun meshMeasuresMatchThePortraitCalibration() {
        val neutral = SmartShutter.faceState(mesh(mouthWidth = 36f, eyeGap = 6f, cornerLift = 0f))!!
        val smiling = SmartShutter.faceState(mesh(mouthWidth = 46f, eyeGap = 5f, cornerLift = 5f))!!
        val closed = SmartShutter.faceState(mesh(mouthWidth = 46f, eyeGap = 2f, cornerLift = 5f))!!
        assertTrue(neutral.smile < SmartShutter.SMILING)
        assertTrue(smiling.smile >= SmartShutter.SMILING)
        assertTrue(smiling.eyes >= SmartShutter.EYES_OPEN)
        assertTrue(closed.eyes < SmartShutter.EYES_OPEN)
        assertNull(SmartShutter.faceState(FloatArray(10)))
    }
}