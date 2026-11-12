/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureReadinessTest {
    @Test
    fun readyAgainAfterExposureWhileSaving() {
        val r = CaptureReadiness(2)
        assertTrue(r.tryStart())
        assertFalse(r.ready)
        r.exposureDone()
        assertTrue(r.ready)
        assertTrue(r.saving)
        r.finished()
        assertFalse(r.saving)
        assertTrue(r.ready)
    }

    @Test
    fun noSecondShotWhileExposing() {
        val r = CaptureReadiness(3)
        assertTrue(r.tryStart())
        assertFalse(r.tryStart())
        assertEquals(1, r.pendingSaves)
    }

    @Test
    fun pendingSavesAreBounded() {
        val r = CaptureReadiness(2)
        assertTrue(r.tryStart()); r.exposureDone()
        assertTrue(r.tryStart()); r.exposureDone()
        assertFalse(r.ready)
        assertFalse(r.tryStart())
        r.finished()
        assertTrue(r.ready)
        assertTrue(r.tryStart())
    }

    @Test
    fun failureDuringExposureFreesTheShutter() {
        val r = CaptureReadiness(2)
        r.tryStart()
        r.finished()
        assertTrue(r.ready)
        assertFalse(r.saving)
    }

    @Test
    fun extraFinishAndResetAreSafe() {
        val r = CaptureReadiness(1)
        r.finished()
        assertEquals(0, r.pendingSaves)
        r.tryStart(); r.exposureDone()
        assertFalse(r.ready)
        r.reset()
        assertTrue(r.ready)
        assertEquals(0, r.pendingSaves)
    }
}
