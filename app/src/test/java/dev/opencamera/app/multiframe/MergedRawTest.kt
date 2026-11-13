/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MergedRawTest {
    private val w = 4624
    private val h = 3472
    private val px = w.toLong() * h

    @Test
    fun enoughMemoryPlansAMergedBurst() {
        val plan = MergedRawPolicy.plan(true, px * 20, w, h, 33_000_000L)
        assertEquals(RawOutput.MERGED_DNG, plan.output)
        assertEquals(MergedRawPolicy.MAX_FRAMES, plan.frames)
        assertNull(plan.reason)
    }

    @Test
    fun longExposuresTakeFewerFramesButAtLeastTwo() {
        assertEquals(4, MergedRawPolicy.frameCount(500_000_000L))
        assertEquals(2, MergedRawPolicy.frameCount(4_000_000_000L))
        assertEquals(8, MergedRawPolicy.frameCount(0L))
    }

    @Test
    fun memoryGuardDegradesToSingleDngThenJpeg() {
        val single = MergedRawPolicy.plan(true, px * 12, w, h, 33_000_000L)
        assertEquals(RawOutput.SINGLE_DNG, single.output)
        assertEquals(1, single.frames)
        assertEquals(RawFallbackReason.LOW_MEMORY, single.reason)

        val none = MergedRawPolicy.plan(true, px * 5, w, h, 33_000_000L)
        assertEquals(RawOutput.JPEG_ONLY, none.output)
        assertEquals(RawFallbackReason.LOW_MEMORY, none.reason)
    }

    @Test
    fun unsupportedCameraTakesJpegOnly() {
        val plan = MergedRawPolicy.plan(false, Long.MAX_VALUE, w, h, 33_000_000L)
        assertEquals(RawOutput.JPEG_ONLY, plan.output)
        assertEquals(RawFallbackReason.UNSUPPORTED, plan.reason)
        assertEquals(RawFallbackReason.UNSUPPORTED, MergedRawPolicy.plan(true, Long.MAX_VALUE, 0, 0, 1L).reason)
    }

    @Test
    fun partialBurstStartsFromWhatWasCaptured() {
        val merged = MergedRawPolicy.Plan(RawOutput.MERGED_DNG, 8, null)
        assertEquals(RawOutput.MERGED_DNG, MergedRawPolicy.start(merged, 3, true))
        assertEquals(RawOutput.SINGLE_DNG, MergedRawPolicy.start(merged, 1, true))
        assertEquals(RawOutput.SINGLE_DNG, MergedRawPolicy.start(merged, 0, true))
        assertEquals(RawOutput.JPEG_ONLY, MergedRawPolicy.start(merged, 8, false))
        val single = MergedRawPolicy.Plan(RawOutput.SINGLE_DNG, 1, RawFallbackReason.LOW_MEMORY)
        assertEquals(RawOutput.SINGLE_DNG, MergedRawPolicy.start(single, 0, true))
    }

    private class Recorder {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Pair<RawOutput, RawFallbackReason>>()
        fun onFailure(step: RawOutput, reason: RawFallbackReason, e: Throwable) {
            failures += step to reason
        }
    }

    @Test
    fun mergedSuccessSkipsTheSingleDng() = runBlocking {
        val r = Recorder()
        val out = RawFallbackChain.run(RawOutput.MERGED_DNG, { r.calls += "merged" }, { r.calls += "single" }, r::onFailure)
        assertEquals(RawOutput.MERGED_DNG, out)
        assertEquals(listOf("merged"), r.calls)
        assertEquals(emptyList<Any>(), r.failures)
    }

    @Test
    fun mergeFailureFallsBackToSingleDngWithReason() = runBlocking {
        val r = Recorder()
        val out = RawFallbackChain.run(
            RawOutput.MERGED_DNG,
            { error("align failed") },
            { r.calls += "single" },
            r::onFailure,
        )
        assertEquals(RawOutput.SINGLE_DNG, out)
        assertEquals(listOf("single"), r.calls)
        assertEquals(listOf(RawOutput.MERGED_DNG to RawFallbackReason.MERGE), r.failures)
    }

    @Test
    fun bothDngsFailingLeavesJpegOnlyWithBothReasons() = runBlocking {
        val r = Recorder()
        val out = RawFallbackChain.run(
            RawOutput.MERGED_DNG,
            { throw OutOfMemoryError("strip") },
            { throw RawStepException(RawFallbackReason.SINGLE_DNG, "DngCreator refused") },
            r::onFailure,
        )
        assertEquals(RawOutput.JPEG_ONLY, out)
        assertEquals(
            listOf(RawOutput.MERGED_DNG to RawFallbackReason.LOW_MEMORY, RawOutput.SINGLE_DNG to RawFallbackReason.SINGLE_DNG),
            r.failures,
        )
    }

    @Test
    fun singleStartNeverTriesTheMerge() = runBlocking {
        val r = Recorder()
        val out = RawFallbackChain.run(RawOutput.SINGLE_DNG, { r.calls += "merged" }, { r.calls += "single" }, r::onFailure)
        assertEquals(RawOutput.SINGLE_DNG, out)
        assertEquals(listOf("single"), r.calls)
        assertEquals(
            RawOutput.JPEG_ONLY,
            RawFallbackChain.run(RawOutput.JPEG_ONLY, { fail("merged") }, { fail("single") }, r::onFailure),
        )
    }

    @Test
    fun cancellationIsNotAFallback() = runBlocking {
        val r = Recorder()
        try {
            RawFallbackChain.run(RawOutput.MERGED_DNG, { throw CancellationException("user") }, { r.calls += "single" }, r::onFailure)
            fail("cancellation swallowed")
        } catch (_: CancellationException) {
        }
        assertEquals(emptyList<String>(), r.calls)
    }
}
