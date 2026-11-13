/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import dev.opencamera.app.develop.raw.DngReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LinearDngWriterTest {
    private val w = 7
    private val h = 5

    private fun sample(x: Int, y: Int, c: Int) = (y * 4000 + x * 300 + c * 77) % 65536

    private val info = LinearDngInfo(
        width = w, height = h, make = "Maker", model = "Phone 1", software = "Open Camera",
        orientation = 6,
        colorMatrix1 = floatArrayOf(0.9f, -0.3f, -0.1f, -0.4f, 1.2f, 0.2f, -0.05f, 0.15f, 0.6f),
        illuminant1 = 17,
        colorMatrix2 = floatArrayOf(1.1f, -0.4f, -0.2f, -0.3f, 1.1f, 0.2f, -0.1f, 0.2f, 0.5f),
        illuminant2 = 21,
        forwardMatrix1 = floatArrayOf(0.6f, 0.2f, 0.15f, 0.25f, 0.7f, 0.05f, 0.03f, 0.1f, 0.7f),
        forwardMatrix2 = floatArrayOf(0.62f, 0.2f, 0.13f, 0.26f, 0.69f, 0.05f, 0.02f, 0.11f, 0.7f),
        asShotNeutral = floatArrayOf(0.52f, 1f, 0.61f),
        baselineExposure = 0.5f,
        exposureTimeNs = 16_666_666L,
        iso = 400,
        fNumber = 1.89f,
        focalLengthMm = 4.74f,
        dateTime = "2026:11:13 09:00:00",
        description = "Merged 8 frames",
    )

    private fun write(rowsPerStrip: Int): ByteArray {
        val out = ByteArrayOutputStream()
        LinearDngWriter.write(out, info, rowsPerStrip) { y0, rows, buf ->
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            for (y in y0 until y0 + rows) for (x in 0 until w) for (c in 0 until 3) b.putShort(sample(x, y, c).toShort())
        }
        return out.toByteArray()
    }

    /** Minimal IFD0 parser: tag -> (type, count, raw value bytes). */
    private fun ifd0(bytes: ByteArray): Map<Int, Triple<Int, Int, ByteBuffer>> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4949, b.getShort(0).toInt() and 0xFFFF)
        assertEquals(42, b.getShort(2).toInt())
        val off = b.getInt(4)
        val n = b.getShort(off).toInt()
        val map = LinkedHashMap<Int, Triple<Int, Int, ByteBuffer>>()
        var last = -1
        for (i in 0 until n) {
            val e = off + 2 + i * 12
            val tag = b.getShort(e).toInt() and 0xFFFF
            assertTrue("tags ascending", tag > last)
            last = tag
            val type = b.getShort(e + 2).toInt()
            val count = b.getInt(e + 4)
            val size = count * when (type) { 1, 2 -> 1; 3 -> 2; 4 -> 4; 5, 10 -> 8; else -> error("type $type") }
            val at = if (size <= 4) e + 8 else b.getInt(e + 8)
            assertTrue("value inside the file", at + size <= bytes.size)
            map[tag] = Triple(type, count, ByteBuffer.wrap(bytes, at, size).slice().order(ByteOrder.LITTLE_ENDIAN))
        }
        assertEquals("single IFD", 0, b.getInt(off + 2 + n * 12))
        return map
    }

    private fun rationals(e: Triple<Int, Int, ByteBuffer>) = FloatArray(e.second) { e.third.getInt(it * 8).toFloat() / e.third.getInt(it * 8 + 4) }

    @Test
    fun tagsParseBackWithTypesAndValues() {
        val tags = ifd0(write(2))
        fun short(tag: Int) = tags.getValue(tag).also { assertEquals(3, it.first) }.third.getShort(0).toInt() and 0xFFFF
        assertEquals(LinearDngWriter.PHOTOMETRIC_LINEAR_RAW, short(LinearDngWriter.TAG_PHOTOMETRIC))
        assertEquals(3, short(LinearDngWriter.TAG_SAMPLES_PER_PIXEL))
        assertEquals(1, short(LinearDngWriter.TAG_COMPRESSION))
        assertEquals(6, short(LinearDngWriter.TAG_ORIENTATION))
        assertEquals(17, short(LinearDngWriter.TAG_ILLUMINANT1))
        assertEquals(21, short(LinearDngWriter.TAG_ILLUMINANT2))
        assertEquals(400, short(LinearDngWriter.TAG_ISO))
        val version = tags.getValue(LinearDngWriter.TAG_DNG_VERSION)
        assertEquals(1, version.first)
        assertArrayEquals(byteArrayOf(1, 4, 0, 0), ByteArray(4) { version.third.get(it) })
        val cm1 = tags.getValue(LinearDngWriter.TAG_COLOR_MATRIX1)
        assertEquals(10, cm1.first)
        assertArrayEquals(info.colorMatrix1, rationals(cm1), 1e-4f)
        assertArrayEquals(info.forwardMatrix1, rationals(tags.getValue(LinearDngWriter.TAG_FORWARD_MATRIX1)), 1e-4f)
        val neutral = tags.getValue(LinearDngWriter.TAG_AS_SHOT_NEUTRAL)
        assertEquals(5, neutral.first)
        assertArrayEquals(info.asShotNeutral, rationals(neutral), 1e-4f)
        assertEquals(0.5f, rationals(tags.getValue(LinearDngWriter.TAG_BASELINE_EXPOSURE))[0], 1e-4f)
        assertEquals(1f / 60, rationals(tags.getValue(LinearDngWriter.TAG_EXPOSURE_TIME))[0], 1e-4f)
        val white = tags.getValue(LinearDngWriter.TAG_WHITE_LEVEL)
        assertEquals(3, white.second)
        assertEquals(65535, white.third.getInt(0))
        val model = tags.getValue(LinearDngWriter.TAG_UNIQUE_CAMERA_MODEL)
        assertEquals(2, model.first)
        assertEquals("Maker Phone 1\u0000", String(ByteArray(model.second) { model.third.get(it) }, Charsets.US_ASCII))
        assertEquals(3, tags.getValue(LinearDngWriter.TAG_STRIP_OFFSETS).second)
    }

    @Test
    fun dngReaderReadsPixelsAndColourBack() {
        for (rowsPerStrip in listOf(1, 2, 64)) {
            val image = DngReader.read(write(rowsPerStrip))
            assertEquals(w, image.width)
            assertEquals(h, image.height)
            assertEquals(3, image.samples)
            assertEquals(65535f, image.white)
            assertEquals(6, image.orientation)
            assertEquals(0.5f, image.baselineExposure, 1e-4f)
            assertArrayEquals(info.asShotNeutral, image.asShotNeutral, 1e-4f)
            assertArrayEquals(info.colorMatrix2, image.colorMatrix2, 1e-4f)
            for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
                assertEquals("pixel $x,$y,$c", sample(x, y, c), image.data[(y * w + x) * 3 + c].code)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidNeutralIsRejected() {
        LinearDngInfo(1, 1, "a", "b", "c", 1, FloatArray(9), 21, asShotNeutral = floatArrayOf(1f, 0f, 1f))
    }
}
