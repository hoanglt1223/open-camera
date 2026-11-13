/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentAutoCaptureTest {
    private val page = floatArrayOf(100f, 100f, 900f, 120f, 880f, 1300f, 120f, 1280f)

    private fun moved(q: FloatArray, dx: Float) = FloatArray(8) { if (it % 2 == 0) q[it] + dx else q[it] }

    private fun DocumentAutoCapture.feed(q: FloatArray?, times: Int) = (1..times).map { onDetection(q, 1000, 1400) }

    @Test
    fun firesOnceWhenThePageHoldsStill() {
        val a = DocumentAutoCapture()
        assertEquals(listOf(false, false, true, false, false), a.feed(page, 5))
    }

    @Test
    fun shakingPageNeverFires() {
        val a = DocumentAutoCapture()
        var dx = 0f
        repeat(10) {
            dx += 60f
            assertFalse(a.onDetection(moved(page, if (it % 2 == 0) dx else -dx), 1000, 1400))
        }
    }

    @Test
    fun smallOutlineIsIgnored() {
        val tiny = floatArrayOf(10f, 10f, 60f, 10f, 60f, 60f, 10f, 60f)
        assertTrue(DocumentAutoCapture().feed(tiny, 6).none { it })
    }

    @Test
    fun rearmsAfterPageIsRemovedOrReplaced() {
        val a = DocumentAutoCapture()
        assertTrue(a.feed(page, 3).last())
        // Lost outline (page lifted) re-arms.
        assertFalse(a.onDetection(null, 1000, 1400))
        assertTrue(a.feed(page, 3).last())
        // A big move (next page laid down elsewhere) re-arms too.
        val next = moved(page, 300f)
        assertEquals(listOf(false, false, true), a.feed(next, 3))
    }

    @Test
    fun badInputIsHarmless() {
        val a = DocumentAutoCapture()
        assertFalse(a.onDetection(floatArrayOf(1f, 2f), 1000, 1400))
        assertFalse(a.onDetection(page, 0, 0))
    }
}