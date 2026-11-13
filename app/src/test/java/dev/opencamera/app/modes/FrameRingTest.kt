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
    fun copiesAreDetachedFromRecycledBuffers() {
        val ring = FrameRing(maxFrames = 2)
        ring.add(frame(16, 0))
        ring.add(frame(16, 1))
        val copies = ring.copyOf { it.timestampNs >= 1 }
        assertEquals(listOf(1L), copies.map { it.timestampNs })
        ring.add(frame(16, 2))
        val reused = ring.recycle(16)
        assertNotNull(reused)
        reused!!.fill(7)
        assertEquals(0, copies[0].nv21[0].toInt())
        assertNull(ring.recycle(32))
    }
}
