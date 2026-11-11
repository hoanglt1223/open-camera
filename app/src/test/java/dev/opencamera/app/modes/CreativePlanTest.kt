/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

import dev.opencamera.pipeline.StackOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativePlanTest {
    @Test
    fun offMeansRegularCapture() {
        assertNull(CreativePlan.of(CreativeMode.OFF, onTripod = false))
    }

    @Test
    fun everyModeHasAPlan() {
        for (mode in CreativeMode.entries - CreativeMode.OFF) {
            val plan = CreativePlan.of(mode, onTripod = false)!!
            assertTrue("$mode needs at least 2 frames", plan.frames >= 2)
        }
        assertEquals(StackOp.MOON, CreativePlan.of(CreativeMode.MOON, false)!!.op)
        assertEquals(StackOp.MEDIAN, CreativePlan.of(CreativeMode.REMOVE_PEOPLE, false)!!.op)
        assertNull(CreativePlan.of(CreativeMode.SPORTS, false)!!.op)
    }

    @Test
    fun moonUnderexposesAndWaitsForTheShakeToSettle() {
        val plan = CreativePlan.of(CreativeMode.MOON, false)!!
        assertTrue(plan.evCompensation < 0f)
        assertEquals(2000L, plan.delayMs)
    }

    @Test
    fun tripodKeepsMotionBlurUnaligned() {
        assertFalse(CreativePlan.of(CreativeMode.LONG_EXPOSURE, onTripod = true)!!.align)
        assertTrue(CreativePlan.of(CreativeMode.LONG_EXPOSURE, onTripod = false)!!.align)
        assertFalse(CreativePlan.of(CreativeMode.LIGHT_TRAILS, onTripod = true)!!.align)
    }

    @Test
    fun passerByRemovalSpreadsFramesOverTime() {
        assertTrue(CreativePlan.of(CreativeMode.REMOVE_PEOPLE, false)!!.stride > 1)
    }

    @Test
    fun focusSweepGoesFromNearToInfinity() {
        val s = CreativePlan.focusSweep(10f, 6)
        assertEquals(6, s.size)
        assertEquals(10f, s.first(), 1e-6f)
        assertEquals(0f, s.last(), 1e-6f)
        for (i in 1 until s.size) assertTrue(s[i] < s[i - 1])
    }

    @Test
    fun fixedFocusCameraHasNoSweep() {
        assertEquals(0, CreativePlan.focusSweep(0f, 6).size)
        assertEquals(0, CreativePlan.focusSweep(5f, 1).size)
    }
}
