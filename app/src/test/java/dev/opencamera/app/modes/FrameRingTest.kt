/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRingTest {
    private fun frame(bytes: Int, ts: Long) = RingFrame(ByteArray(bytes), 4, 4, ts, 0)

    @Test
    fun byteBudgetCapsTheRing() {
        val ring = FrameRing(maxFrames = 10, budgetBytes = 1000)
        repeat(10) { ring.add(frame(300, it.toLong())) }
        assertEquals(3, ring.size)
        assertEquals(900L, ring.bytes)
        assertEquals(listOf(7L, 8L, 9L), ring.snapshot().map { it.timestampNs })
    }

    @Test
    fun heldRingDoesNotReuseSnapshotBuffers() {
        val ring = FrameRing(maxFrames = 2)
        ring.add(frame(16, 0))
        ring.add(frame(16, 1))
        assertNotNull(ring.recycle(16))
        ring.add(frame(16, 2))
        ring.hold()
        assertNull(ring.recycle(16))
        ring.release()
        assertNotNull(ring.recycle(16))
        // Size mismatch never reuses.
        ring.add(frame(16, 3))
        assertNull(ring.recycle(32))
    }
}
