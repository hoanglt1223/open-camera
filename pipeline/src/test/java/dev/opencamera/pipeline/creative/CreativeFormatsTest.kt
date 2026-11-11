/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CreativeFormatsTest {
    private fun sky(w: Int, h: Int, noise: Int = 12, seed: Int = 1): ByteArray {
        val rnd = Random(seed)
        return ByteArray(w * h) { (8 + rnd.nextInt(noise)).toByte() }
    }

    private fun disc(img: ByteArray, w: Int, cx: Int, cy: Int, r: Int, v: Int) {
        val h = img.size / w
        for (y in 0 until h) for (x in 0 until w) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) img[y * w + x] = v.toByte()
        }
    }

    @Test
    fun detectsBrightDiscOnDarkSky() {
        val w = 640; val h = 480
        val img = sky(w, h)
        disc(img, w, 400, 150, 30, 230)
        val d = MoonDetector.detect(img, w, h)
        assertNotNull(d)
        d!!
        assertEquals(400f / w, d.cx, 0.02f)
        assertEquals(150f / h, d.cy, 0.02f)
        assertEquals(30f / h, d.radius, 0.02f)
        assertTrue(d.contrast > 3f)
    }

    @Test
    fun rejectsBrightDayScenesStreaksAndEmptyFrames() {
        val w = 320; val h = 240
        assertNull(MoonDetector.detect(ByteArray(w * h) { 150.toByte() }, w, h))
        assertNull(MoonDetector.detect(sky(w, h), w, h))
        val streak = sky(w, h)
        for (x in 20 until 300) for (y in 100 until 106) streak[y * w + x] = 240.toByte()
        assertNull(MoonDetector.detect(streak, w, h))
        assertNull(MoonDetector.detect(ByteArray(10), w, h))
    }

    @Test
    fun honoursRowStride() {
        val w = 320; val h = 240; val stride = 384
        val img = ByteArray(stride * h) { 10 }
        for (y in 0 until h) for (x in 0 until w) {
            if ((x - 100) * (x - 100) + (y - 120) * (y - 120) <= 20 * 20) img[y * stride + x] = 220.toByte()
        }
        val d = MoonDetector.detect(img, w, h, stride)
        assertNotNull(d)
        assertEquals(100f / w, d!!.cx, 0.03f)
    }

    private fun fakeJpeg(withXmp: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        // APP1 Exif
        val exif = "Exif\u0000\u0000abcdef".toByteArray(Charsets.ISO_8859_1)
        out.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte())); out.write(exif)
        if (withXmp) {
            val xmp = "http://ns.adobe.com/xap/1.0/\u0000<old/>".toByteArray(Charsets.ISO_8859_1)
            out.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (xmp.size + 2).toByte())); out.write(xmp)
        }
        // DQT + SOS + data + EOI
        out.write(byteArrayOf(0xff.toByte(), 0xdb.toByte(), 0, 4, 1, 2))
        out.write(byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 4, 9, 9, 7, 7, 7, 0xff.toByte(), 0xd9.toByte()))
        return out.toByteArray()
    }

    @Test
    fun motionPhotoEmbedsXmpAndTrailingVideo() {
        for (withXmp in listOf(false, true)) {
            val jpeg = fakeJpeg(withXmp)
            val mp4 = ByteArray(5000) { (it * 7).toByte() }
            val file = MotionPhotoFormat.build(jpeg, mp4, 1_500_000)
            val text = String(file, Charsets.ISO_8859_1)
            assertTrue(text.contains("GCamera:MotionPhoto=\"1\""))
            assertTrue(text.contains("Item:Length=\"5000\""))
            assertTrue(!text.contains("<old/>"))
            assertTrue(text.indexOf("Exif") < text.indexOf("GCamera"))
            assertEquals(5000, MotionPhotoFormat.videoLength(file))
            assertArrayEquals(mp4, MotionPhotoFormat.extractVideo(file))
            // The JPEG part still ends with EOI just before the video.
            assertEquals(0xd9.toByte(), file[file.size - mp4.size - 1])
            // Re-embedding replaces the previous container instead of nesting it.
            val again = MotionPhotoFormat.build(file.copyOf(file.size - mp4.size), mp4, 0)
            assertEquals(1, Regex("GCamera:MotionPhoto=").findAll(String(again, Charsets.ISO_8859_1)).count())
        }
    }

    @Test
    fun clipSurvivesReRendering() {
        val mp4 = ByteArray(3000) { (it * 3).toByte() }
        val original = MotionPhotoFormat.build(fakeJpeg(false), mp4, 1_234_567)
        val rendered = fakeJpeg(true)
        val carried = MotionPhotoFormat.carryOver(original, rendered)
        assertArrayEquals(mp4, MotionPhotoFormat.extractVideo(carried))
        assertTrue(String(carried, Charsets.ISO_8859_1).contains("PresentationTimestampUs=\"1234567\""))
        // Not a motion photo: unchanged.
        assertArrayEquals(rendered, MotionPhotoFormat.carryOver(fakeJpeg(false), rendered))
    }

    @Test
    fun plainJpegIsNotAMotionPhoto() {
        assertNull(MotionPhotoFormat.videoLength(fakeJpeg(true)))
        assertNull(MotionPhotoFormat.videoLength(ByteArray(3)))
    }
}
