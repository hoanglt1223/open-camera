/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstSequenceTest {
    private val caps = ExposureCaps(
        minExposureNs = 10_000L, maxExposureNs = 32_000_000_000L, minIso = 100, maxIso = 6400,
    )

    @Test
    fun nightKeepsLongExposureWithoutCap() {
        val plan = BurstPlan(BurstKind.NIGHT, 6, exposureTimeNs = 1_000_000_000L, iso = 6400)
        val frames = BurstSequence.frames(plan, caps, 50_000_000L, 1600, minFrameDurationNs = 50_000_000L)
        assertEquals(6, frames.size)
        frames.forEach {
            assertEquals(1_000_000_000L, it.exposureTimeNs)
            assertEquals(6400, it.iso)
            assertEquals(1_000_000_000L, it.frameDurationNs)
        }
    }

    @Test
    fun astroIsLimitedBySensorMaximum() {
        val small = caps.copy(maxExposureNs = 8_000_000_000L)
        val plan = BurstPlan(BurstKind.ASTRO, 4, exposureTimeNs = 16_000_000_000L, iso = 1600)
        val frame = BurstSequence.frames(plan, small, null, null)[0]
        assertEquals(8_000_000_000L, frame.exposureTimeNs)
    }

    @Test
    fun hdrAppliesEvToMeteredTime() {
        val plan = BurstPlan(BurstKind.HDR, 8, evCompensation = -1f)
        val frames = BurstSequence.frames(plan, caps, 40_000_000L, 400)
        assertEquals(8, frames.size)
        assertEquals(20_000_000L, frames[0].exposureTimeNs)
        assertEquals(400, frames[0].iso)
    }

    @Test
    fun shortFrameUsesStreamMinimumDuration() {
        val plan = BurstPlan(BurstKind.HDR, 2, evCompensation = 0f)
        val frame = BurstSequence.frames(plan, caps, 10_000_000L, 100, minFrameDurationNs = 50_000_000L)[0]
        assertEquals(10_000_000L, frame.exposureTimeNs)
        assertEquals(50_000_000L, frame.frameDurationNs)
    }

    @Test
    fun isoIsClampedToSensorRange() {
        val plan = BurstPlan(BurstKind.NIGHT, 6, exposureTimeNs = 100_000_000L, iso = 25600)
        assertEquals(6400, BurstSequence.frames(plan, caps, null, null)[0].iso)
    }

    @Test
    fun unknownExposureGivesNoSequence() {
        assertTrue(BurstSequence.frames(BurstPlan(BurstKind.HDR, 6), caps, null, null).isEmpty())
    }

    @Test
    fun timeoutIsFrameTimePlusBoundedSlack() {
        val plan = BurstPlan(BurstKind.NIGHT, 6, exposureTimeNs = 1_000_000_000L, iso = 6400)
        val frames = BurstSequence.frames(plan, caps, null, null)
        assertEquals(6000L, BurstSequence.expectedMs(frames))
        val timeout = BurstSequence.timeoutMs(frames)
        assertTrue(timeout in 6001L..10_000L)
    }

    @Test
    fun astroTimeoutCoversSlowLongFrames() {
        val plan = BurstPlan(BurstKind.ASTRO, 4, exposureTimeNs = 16_000_000_000L, iso = 576)
        val timeout = BurstSequence.timeoutMs(BurstSequence.frames(plan, caps, null, null))
        // Measured on gauguin: 4 frames end 67.2 s after the session starts.
        assertTrue(timeout in 70_000L..80_000L)
    }
}
