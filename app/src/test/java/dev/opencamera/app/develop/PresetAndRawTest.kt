/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.preset.CubeIo
import dev.opencamera.app.develop.preset.Preset
import dev.opencamera.app.develop.preset.PresetCodec
import dev.opencamera.app.develop.preset.PresetFormatException
import dev.opencamera.app.develop.preset.PresetRepository
import dev.opencamera.app.develop.raw.DngReader
import dev.opencamera.app.develop.raw.Lj92
import dev.opencamera.app.develop.raw.RawDevelop
import dev.opencamera.app.develop.raw.RawFormatException
import dev.opencamera.pipeline.looks.CubeParseException
import dev.opencamera.pipeline.looks.Lut3d
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.random.Random

class PresetAndRawTest {
    @Test
    fun oneDimensionalCubeBecomes3d() {
        val text = "TITLE \"inv\"\nLUT_1D_SIZE 2\n1 1 1\n0 0 0\n"
        val lut = CubeIo.parse(text)
        assertEquals(33, lut.size)
        assertEquals(1f, lut.data[0], 1e-6f)
        assertEquals(0f, lut.data[lut.data.size - 1], 1e-6f)
    }

    @Test
    fun malformedCubesAreRejected() {
        for (bad in listOf("LUT_3D_SIZE 2\n0 0 0\n", "LUT_1D_SIZE 3\n0 0\n", "LUT_3D_SIZE 999\n", "LUT_1D_SIZE 2\n0 0 nan\n1 1 1\n", "")) {
            try {
                CubeIo.parse(bad); throw AssertionError("accepted: $bad")
            } catch (_: CubeParseException) {
            }
        }
    }

    @Test
    fun presetFileRoundTripWithInlineLut() {
        val p = Preset("x", "Moody", setOf(Group.LIGHT, Group.CURVE), EditStack().with(Params.EXPOSURE, -0.3f).with(Params.SATURATION, 40f))
        val cube = CubeIo.write(Lut3d.identity(5), "id")
        val (back, lut) = PresetCodec.decode(PresetCodec.encode(p, cube), "y")
        assertEquals("Moody", back.name)
        assertEquals(setOf(Group.LIGHT, Group.CURVE), back.groups)
        assertEquals(-0.3f, back.stack[Params.EXPOSURE])
        // Values outside the preset's groups are dropped on load.
        assertEquals(0f, back.stack[Params.SATURATION])
        assertEquals(5, lut!!.size)
    }

    @Test(expected = PresetFormatException::class)
    fun wrongPresetFormatIsRejected() {
        PresetCodec.decode("{\"format\":\"something\"}", "id")
    }

    @Test
    fun presetStrengthAndRepository() {
        val dir = Files.createTempDirectory("presets").toFile()
        try {
            val repo = PresetRepository(dir) { emptyList() }
            val a = repo.save("A", setOf(Group.LIGHT), EditStack().with(Params.EXPOSURE, 2f))
            repo.save("B", setOf(Group.COLOR), EditStack().with(Params.TEMP, 10f))
            assertEquals(listOf("A", "B"), repo.user().map { it.name })
            repo.move(a.id, 1)
            assertEquals(listOf("B", "A"), repo.user().map { it.name })
            val half = repo.user().first { it.name == "A" }.applyTo(EditStack().with(Params.EXPOSURE, 1f), 0.5f)
            assertEquals(1f, half[Params.EXPOSURE], 1e-4f)
            assertTrue(repo.builtIn().any { it.name == "Matte" })
            File(dir, "junk.ocpreset").writeText("{broken")
            assertEquals(2, repo.user().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun File(dir: java.io.File, name: String) = java.io.File(dir, name)

    // ---- LJ92 -------------------------------------------------------------------------

    /** Minimal lossless-JPEG encoder (predictor 1, one table with 5-bit codes) for tests. */
    private fun lj92Encode(data: IntArray, w: Int, h: Int, comps: Int, precision: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 255) }
        out.write(0xFF); out.write(0xD8)
        // DHT: 17 symbols (0..16), all of length 5.
        out.write(0xFF); out.write(0xC4); u16(2 + 1 + 16 + 17)
        out.write(0x00)
        for (i in 1..16) out.write(if (i == 5) 17 else 0)
        for (s in 0..16) out.write(s)
        out.write(0xFF); out.write(0xC3); u16(8 + 3 * comps)
        out.write(precision); u16(h); u16(w); out.write(comps)
        for (c in 0 until comps) { out.write(c + 1); out.write(0x11); out.write(0) }
        out.write(0xFF); out.write(0xDA); u16(6 + 2 * comps)
        out.write(comps)
        for (c in 0 until comps) { out.write(c + 1); out.write(0x00) }
        out.write(1); out.write(0); out.write(0)
        var acc = 0L; var n = 0
        fun bits(v: Int, len: Int) {
            for (i in len - 1 downTo 0) {
                acc = (acc shl 1) or ((v shr i) and 1).toLong(); n++
                if (n == 8) {
                    val b = acc.toInt() and 255
                    out.write(b); if (b == 0xFF) out.write(0)
                    acc = 0; n = 0
                }
            }
        }
        val stride = w * comps
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until comps) {
            val i = y * stride + x * comps + c
            val pred = when {
                x == 0 && y == 0 -> 1 shl (precision - 1)
                y == 0 -> data[i - comps]
                x == 0 -> data[i - stride]
                else -> data[i - comps]
            }
            var diff = (data[i] - pred) and 0xFFFF
            if (diff >= 32768) diff -= 65536
            val ssss = if (diff == 0) 0 else 32 - Integer.numberOfLeadingZeros(kotlin.math.abs(diff))
            bits(ssss, 5)
            if (ssss in 1..15) bits(if (diff < 0) diff + (1 shl ssss) - 1 else diff, ssss)
        }
        if (n > 0) bits((1 shl (8 - n)) - 1, 8 - n)
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    @Test
    fun lj92DecodesWhatItEncodes() {
        val rnd = Random(9)
        val w = 37; val h = 11; val comps = 2
        val data = IntArray(w * h * comps) { rnd.nextInt(0, 4096) }
        val f = Lj92.decode(lj92Encode(data, w, h, comps, 12))
        assertEquals(w, f.width); assertEquals(h, f.height); assertEquals(comps, f.components)
        assertArrayEquals(data, IntArray(f.data.size) { f.data[it].code })
    }

    @Test(expected = RawFormatException::class)
    fun lj92RejectsBaselineJpeg() {
        Lj92.decode(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 0x11, 0))
    }

    // ---- DNG --------------------------------------------------------------------------

    /** Writes a little-endian CFA DNG: one IFD, 16-bit uncompressed or LJ92 tiles. */
    private fun dng(w: Int, h: Int, raw: IntArray, lj92: Boolean): ByteArray {
        data class E(val tag: Int, val type: Int, val values: List<Number>)
        val image: ByteArray = if (lj92) lj92Encode(raw.let { r ->
            // Two components side by side, as DNG writers do for CFA tiles.
            IntArray(r.size) { r[it] }
        }, w / 2, h, 2, 16) else ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> raw.forEach { b.putShort(it.toShort()) } }.array()
        val entries = mutableListOf(
            E(254, 4, listOf(0)), E(256, 4, listOf(w)), E(257, 4, listOf(h)), E(258, 3, listOf(16)),
            E(259, 3, listOf(if (lj92) 7 else 1)), E(262, 3, listOf(32803)), E(274, 3, listOf(1)), E(277, 3, listOf(1)),
            E(33421, 3, listOf(2, 2)), E(33422, 1, listOf(0, 1, 1, 2)), E(50706, 1, listOf(1, 4, 0, 0)),
            E(50714, 4, listOf(64)), E(50717, 4, listOf(4095)),
            E(50728, 5, listOf(1, 2, 1, 1, 1, 2)), // AsShotNeutral 0.5, 1, 0.5
        )
        if (lj92) {
            entries += E(322, 4, listOf(w)); entries += E(323, 4, listOf(h)); entries += E(324, 4, listOf(0)); entries += E(325, 4, listOf(image.size))
        } else {
            entries += E(273, 4, listOf(0)); entries += E(278, 4, listOf(h)); entries += E(279, 4, listOf(image.size))
        }
        entries.sortBy { it.tag }
        val ifdOff = 8
        val ifdSize = 2 + entries.size * 12 + 4
        val extra = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        val extraOff = ifdOff + ifdSize
        val dataOff = extraOff + 256
        val buf = ByteBuffer.allocate(dataOff + image.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()); buf.put('I'.code.toByte()); buf.putShort(42); buf.putInt(ifdOff)
        buf.putShort(entries.size.toShort())
        for (e in entries) {
            buf.putShort(e.tag.toShort()); buf.putShort(e.type.toShort())
            val count = if (e.type == 5) e.values.size / 2 else e.values.size
            buf.putInt(count)
            val size = when (e.type) { 1 -> 1; 3 -> 2; 4 -> 4; 5 -> 8; else -> 1 } * count
            val vals = if (e.tag == 273 || e.tag == 324) listOf(dataOff) else e.values
            if (size <= 4) {
                val start = buf.position()
                for (v in vals) when (e.type) { 1 -> buf.put(v.toByte()); 3 -> buf.putShort(v.toShort()); else -> buf.putInt(v.toInt()) }
                buf.position(start + 4)
            } else {
                buf.putInt(extraOff + extra.position())
                for (v in vals) extra.putInt(v.toInt())
            }
        }
        buf.putInt(0)
        buf.put(extra.array())
        buf.put(image)
        return buf.array()
    }

    @Test
    fun readsUncompressedAndLosslessDngAlike() {
        val w = 16; val h = 8
        val raw = IntArray(w * h) { i -> val x = i % w; val y = i / w; 64 + ((x + y * 3) * 17) % 3000 }
        val a = DngReader.read(dng(w, h, raw, lj92 = false))
        val b = DngReader.read(dng(w, h, raw, lj92 = true))
        assertEquals(w, a.width); assertEquals(h, a.height)
        assertArrayEquals(IntArray(raw.size) { raw[it] }, IntArray(raw.size) { a.data[it].code })
        assertArrayEquals(IntArray(raw.size) { a.data[it].code }, IntArray(raw.size) { b.data[it].code })
        assertArrayEquals(intArrayOf(0, 1, 1, 2), a.cfa)
        assertEquals(64f, a.black[0]); assertEquals(4095f, a.white)
        assertEquals(0.5f, a.asShotNeutral!![0], 1e-6f)
    }

    @Test
    fun neutralPatchDevelopsToGrey() {
        val w = 16; val h = 16
        // Camera sees a grey card under a light with neutral (0.5, 1, 0.5): R and B read half of G.
        val raw = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val g = 2000
            when ((y and 1) * 2 + (x and 1)) { 0 -> 64 + (g - 64) / 2; 3 -> 64 + (g - 64) / 2; else -> g }
        }
        val img = DngReader.read(dng(w, h, raw, lj92 = false))
        for (half in listOf(true, false)) {
            val lin = RawDevelop.develop(img, half, threads = 2)
            val rgb = lin.rgb!!
            val mid = (lin.height / 2 * lin.width + lin.width / 2) * 3
            assertEquals(rgb[mid + 1], rgb[mid], 0.01f)
            assertEquals(rgb[mid + 1], rgb[mid + 2], 0.01f)
            assertTrue(rgb[mid + 1] > 0.3f)
            // Half-float output agrees with the float output.
            val hb = RawDevelop.develop(img, half, threads = 2, toHalf = true).half!!.asShortBuffer()
            assertEquals(RawDevelop.toHalf(rgb[mid + 1]), hb.get(mid / 3 * 4 + 1))
        }
    }

    @Test
    fun halfFloatConversion() {
        assertEquals(0x3C00.toShort(), RawDevelop.toHalf(1f))
        assertEquals(0x3800.toShort(), RawDevelop.toHalf(0.5f))
        assertEquals(0x5400.toShort(), RawDevelop.toHalf(64f))
        assertEquals(0.toShort(), RawDevelop.toHalf(-1f))
    }

    @Test
    fun garbageIsNotADng() {
        for (bad in listOf(ByteArray(0), "hello world".toByteArray(), byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 0, 0))) {
            try {
                DngReader.read(bad); throw AssertionError("accepted garbage")
            } catch (_: RawFormatException) {
            } catch (_: IndexOutOfBoundsException) {
                throw AssertionError("crashed instead of rejecting")
            }
        }
    }

    @Test
    fun opcodeListWithoutGainMapIsEmpty() {
        assertNull(DngReader.parseOpcodes(null).firstOrNull())
        val bytes = ByteBuffer.allocate(4 + 16 + 4).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(1).putInt(0).putInt(0).putInt(4).putInt(0).array()
        assertTrue(DngReader.parseOpcodes(bytes).isEmpty())
    }
}
