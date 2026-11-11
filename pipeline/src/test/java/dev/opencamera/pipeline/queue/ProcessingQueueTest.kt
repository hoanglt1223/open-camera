/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingQueueTest {
    private var now = 1_000L
    private val store = InMemoryJobStore()
    private val queue = ProcessingQueue(store, { now }, maxAttempts = 3, baseBackoffMs = 2_000)

    @Test
    fun happyPath() {
        val job = queue.enqueue("content://a", "vivid", 1f, "none")
        assertEquals(JobState.QUEUED, job.state)
        assertEquals(1, queue.pendingCount())
        val claimed = queue.claimNext()!!
        assertEquals(JobState.RUNNING, claimed.state)
        assertNull(queue.claimNext())
        queue.markWritten(job.id, "content://out")
        // A WRITTEN job is handed back so the caller can finish publishing it.
        assertEquals(JobState.WRITTEN, queue.claimNext()!!.state)
        assertEquals(JobState.DONE, queue.markDone(job.id).state)
        assertEquals(0, queue.pendingCount())
        assertNull(queue.nextDueAt())
    }

    @Test
    fun enqueueIsIdempotentPerSource() {
        val a = queue.enqueue("content://a", "vivid", 1f, "none")
        val b = queue.enqueue("content://a", "natural", 0.5f, "film_border")
        assertEquals(a.id, b.id)
        assertEquals(1, store.all().size)
    }

    @Test
    fun retriesWithExponentialBackoffThenFails() {
        val job = queue.enqueue("content://a", null, 1f, "exif_strip")
        queue.claimNext()
        var j = queue.markFailed(job.id, "io", retryable = true)
        assertEquals(JobState.QUEUED, j.state)
        assertEquals(now + 2_000, j.nextAttemptAt)
        assertNull("not due yet", queue.claimNext())
        now += 2_000
        queue.claimNext()!!
        j = queue.markFailed(job.id, "io", retryable = true)
        assertEquals(now + 4_000, j.nextAttemptAt)
        now += 4_000
        queue.claimNext()!!
        j = queue.markFailed(job.id, "io", retryable = true)
        assertEquals(JobState.FAILED, j.state)
        assertEquals(3, j.attempts)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun nonRetryableFailsImmediately() {
        val job = queue.enqueue("content://a", null, 1f, "none")
        queue.claimNext()
        assertEquals(JobState.FAILED, queue.markFailed(job.id, "decode", retryable = false).state)
    }

    @Test
    fun recoverRequeuesInterruptedJobsAndKeepsWrittenOnes() {
        val a = queue.enqueue("content://a", null, 1f, "none")
        val b = queue.enqueue("content://b", null, 1f, "none")
        queue.claimNext() // a RUNNING
        queue.claimNext() // b RUNNING
        queue.markWritten(b.id, "content://out-b")
        // Process death here; a new worker starts.
        val restarted = ProcessingQueue(store, { now })
        restarted.recover()
        assertEquals(JobState.QUEUED, restarted.get(a.id)!!.state)
        assertEquals(1, restarted.get(a.id)!!.attempts)
        val wb = restarted.get(b.id)!!
        assertEquals(JobState.WRITTEN, wb.state)
        assertEquals("content://out-b", wb.outputUri)
        assertEquals(2, restarted.pendingCount())
        // The written job is due now, the interrupted one after its backoff.
        assertEquals(b.id, restarted.claimNext()!!.id)
    }

    @Test
    fun crashLoopEndsInFailed() {
        val job = queue.enqueue("content://poison", null, 1f, "none")
        repeat(3) {
            now += 60_000
            queue.claimNext()!!
            queue.recover()
        }
        assertEquals(JobState.FAILED, queue.get(job.id)!!.state)
    }

    @Test
    fun publishFailureKeepsOutput() {
        val job = queue.enqueue("content://a", null, 1f, "none")
        queue.claimNext()
        queue.markWritten(job.id, "content://out")
        val j = queue.markFailed(job.id, "publish", retryable = true)
        assertEquals(JobState.WRITTEN, j.state)
        assertEquals("content://out", j.outputUri)
    }

    @Test
    fun illegalTransitionsThrow() {
        val job = queue.enqueue("content://a", null, 1f, "none")
        assertTrue(runCatching { queue.markDone(job.id) }.isFailure)
        assertTrue(runCatching { queue.markWritten(job.id, "x") }.isFailure)
        assertTrue(runCatching { queue.markFailed(job.id, "x", true) }.isFailure)
    }

    @Test
    fun finishedJobsArePrunedAfterADay() {
        val job = queue.enqueue("content://a", null, 1f, "none")
        queue.claimNext()
        queue.markWritten(job.id, "o")
        queue.markDone(job.id)
        now += 25 * 60 * 60 * 1000L
        queue.recover()
        assertNull(queue.get(job.id))
    }
}
