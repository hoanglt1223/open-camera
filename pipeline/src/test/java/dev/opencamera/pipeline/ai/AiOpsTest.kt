/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import dev.opencamera.ml.tasks.Mask
import dev.opencamera.pipeline.queue.InMemoryJobStore
import dev.opencamera.pipeline.queue.JobState
import dev.opencamera.pipeline.queue.ProcessingQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiOpsTest {
    @Test
    fun roundTrip() {
        val ops = AiOps(portraitAperture = 0.8f, focusX = 0.2f, focusY = 0.7f, beauty = 0.3f, upscale = 2, keepSource = true)
        assertEquals(ops, AiOps.fromJson(ops.toJson()))
    }

    @Test
    fun badJsonMeansNoMlSteps() {
        assertNull(AiOps.fromJson(null))
        assertNull(AiOps.fromJson(""))
        assertNull(AiOps.fromJson("{not json"))
        val clamped = AiOps.fromJson("""{"aperture":7,"beauty":-1,"upscale":3,"budget":5}""")!!
        assertEquals(1f, clamped.portraitAperture)
        assertEquals(0f, clamped.beauty)
        assertEquals(1, clamped.upscale)
        assertEquals(1_000L, clamped.budgetMs)
        assertTrue(AiOps().isEmpty)
    }

    @Test
    fun focusOnBackgroundSwapsSharpRegion() {
        val m = Mask(2, 1, floatArrayOf(1f, 0f)) // subject on the left
        assertEquals(1f, BokehMath.sharpness(m, null, null)[0])
        assertEquals(1f, BokehMath.sharpness(m, 0.1f, 0.5f)[0])
        val bg = BokehMath.sharpness(m, 0.9f, 0.5f)
        assertEquals(0f, bg[0])
        assertEquals(1f, bg[1])
    }

    @Test
    fun depthSharpnessFallsOffAwayFromFocus() {
        val depth = Mask(3, 1, floatArrayOf(1f, 2f, 5f))
        val s = BokehMath.sharpnessFromDepth(depth, 0.1f, 0.5f, 1f)
        assertEquals(1f, s[0])
        assertEquals(1f, s[1])
        assertEquals(0f, s[2])
    }

    @Test
    fun apertureMapping() {
        assertEquals(16f, BokehMath.fNumber(0f))
        assertEquals(1f, BokehMath.fNumber(1f))
        assertEquals(0f, BokehMath.radius(0f, 1000))
        assertTrue(BokehMath.radius(1f, 1000) in 20f..40f)
    }

    @Test
    fun editReplacesFinishedJobButNotPendingOne() {
        val q = ProcessingQueue(InMemoryJobStore())
        val first = q.enqueue("content://media/1", "vivid", 1f, "none")
        assertEquals(first.id, q.enqueue("content://media/1", null, 1f, "none", AiOps(denoise = 0.5f).toJson()).id)
        q.claimNext()
        q.markWritten(first.id, "content://media/2")
        q.markDone(first.id)
        val edit = q.enqueue("content://media/1", null, 1f, "none", AiOps(denoise = 0.5f, keepSource = true).toJson())
        assertNotEquals(first.id, edit.id)
        assertEquals(JobState.QUEUED, edit.state)
        assertEquals(0.5f, AiOps.fromJson(edit.ops)!!.denoise)
    }
}
