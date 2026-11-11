/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.queue

import dev.opencamera.pipeline.MultiFrameProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class BurstArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTripIsLossless() {
        val w = 64
        val h = 48
        val rng = Random(1)
        val frames = List(3) { ByteArray(w * h * 3 / 2) { rng.nextInt(256).toByte() } }
        val dir = File(tmp.root, "b1")
        val n = BurstArchive.write(dir, w, h, frames.size, mapOf("mode" to "HDR", "rotation" to "90")) { frames[it] }
        assertEquals(3, n)
        val a = BurstArchive.open(dir)!!
        assertEquals(3, a.frameCount)
        assertEquals("HDR", a.meta["mode"])
        assertEquals("90", a.meta["rotation"])
        frames.forEachIndexed { i, f -> assertArrayEquals(f, BurstArchive.readFrame(a, i)) }
    }

    @Test
    fun incompleteArchiveIsIgnored() {
        val dir = File(tmp.root, "b2").apply { mkdirs() }
        File(dir, "frame_0.nv21z").writeBytes(ByteArray(10))
        assertNull(BurstArchive.open(dir))
    }

    @Test
    fun missingFrameIsIgnored() {
        val dir = File(tmp.root, "b3")
        BurstArchive.write(dir, 8, 8, 2, emptyMap()) { ByteArray(96) }
        File(dir, "frame_1.nv21z").delete()
        assertNull(BurstArchive.open(dir))
    }

    @Test
    fun zoomCropIsCentredAndEven() {
        assertNull(MultiFrameProcessor.zoomCrop(4000, 3000, 1f))
        assertArrayEquals(intArrayOf(1000, 750, 2000, 1500), MultiFrameProcessor.zoomCrop(4000, 3000, 2f))
        // Off-centre target stays inside the frame.
        assertArrayEquals(intArrayOf(2000, 0, 2000, 1500), MultiFrameProcessor.zoomCrop(4000, 3000, 2f, 0.95f, 0.05f))
    }
}
