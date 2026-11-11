/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import dev.opencamera.capture.BurstKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ManualStillTest {
    @Test
    fun longManualExposureBecomesOneFrameManualPlan() {
        val plan = ManualStill.plan(1_600_000_000L, 400)!!
        assertEquals(BurstKind.SINGLE, plan.kind)
        assertEquals(1, plan.frameCount)
        assertEquals(1_600_000_000L, plan.exposureTimeNs)
        assertEquals(400, plan.iso)
    }

    @Test
    fun shortOrAutoExposureStaysOnCameraX() {
        assertNull(ManualStill.plan(100_000_000L, 400))
        assertNull(ManualStill.plan(null, 400))
        assertNull(ManualStill.plan(1_000_000_000L, null))
    }

    @Test
    fun failedCamera2BurstFallsBackToSingleFrame() = runBlocking {
        val errors = mutableListOf<String>()
        val result = ManualStill.withFallback(
            primary = { error("Session not configured") },
            fallback = { "camerax" },
        ) { step, _ -> errors += step }
        assertEquals("camerax", result)
        assertEquals(listOf("primary"), errors)
    }

    @Test
    fun bothFailingGivesNullWithoutThrowing() = runBlocking {
        val errors = mutableListOf<String>()
        val result = ManualStill.withFallback<String>(
            primary = { error("burst") },
            fallback = { error("single") },
        ) { step, _ -> errors += step }
        assertNull(result)
        assertEquals(listOf("primary", "fallback"), errors)
    }

    @Test
    fun noFallbackGivesNull() = runBlocking {
        assertNull(ManualStill.withFallback<String>({ error("x") }, null) { _, _ -> })
    }

    @Test
    fun cancellationIsNotAFailure() = runBlocking {
        var fallbackRan = false
        try {
            ManualStill.withFallback(
                primary = { throw CancellationException("shutter tapped") },
                fallback = { fallbackRan = true; "camerax" },
            ) { _, _ -> fail("cancel reported as failure") }
            fail("cancellation swallowed")
        } catch (_: CancellationException) {
        }
        assertEquals(false, fallbackRan)
    }
}
