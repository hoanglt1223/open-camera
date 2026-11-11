/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import dev.opencamera.app.develop.export.CrsXmp
import dev.opencamera.app.develop.export.XmpWriter
import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.model.SourceRef
import dev.opencamera.app.develop.model.StackCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpTest {
    private val lightroomish = EditStack()
        .with(Params.EXPOSURE, 0.65f).with(Params.CONTRAST, 12f).with(Params.HIGHLIGHTS, -60f).with(Params.SHADOWS, 45f)
        .with(Params.WHITES, 10f).with(Params.BLACKS, -8f).with(Params.TEMP, 15f).with(Params.TINT, -4f)
        .with(Params.VIBRANCE, 20f).with(Params.SATURATION, -5f).with(Params.CLARITY, 18f).with(Params.TEXTURE, 9f)
        .with(Params.DEHAZE, 7f).with(Params.SHARPEN, 40f).with(Params.SHARPEN_RADIUS, 1.2f).with(Params.NR_LUMA, 25f)
        .with(Params.VIGNETTE, -22f).with(Params.GRAIN, 15f).with(Params.hsl("orange", "lum"), 10f)
        .with(Params.hsl("blue", "sat"), -30f).with(Params.grade("shadows", "hue"), 210f).with(Params.grade("shadows", "sat"), 20f)
        .with(Params.PC_SHADOWS, 10f).with(Params.CROP_LEFT, 0.1f).with(Params.CROP_RIGHT, 0.9f).with(Params.CROP_ANGLE, -1.5f)
        .withCurve("rgb", listOf(CurvePoint(0f, 0f), CurvePoint(64 / 255f, 50 / 255f), CurvePoint(1f, 1f)))
        .withCurve("r", listOf(CurvePoint(0f, 10 / 255f), CurvePoint(1f, 1f)))

    @Test
    fun crsRoundTripKeepsMappedSliders() {
        val xml = CrsXmp.write(lightroomish)
        assertTrue(xml.contains("crs:Exposure2012=\"+0.65\""))
        assertTrue(xml.contains("crs:Highlights2012=\"-60\""))
        assertTrue(xml.contains("<crs:ToneCurvePV2012>"))
        val back = CrsXmp.read(xml)
        for (k in lightroomish.values.keys) {
            assertEquals(k, lightroomish[k], back[k], if (k == Params.EXPOSURE || k == Params.SHARPEN_RADIUS || k.startsWith("crop.")) 0.01f else 0.5f)
        }
        assertEquals(lightroomish.curve("rgb").size, back.curve("rgb").size)
        assertEquals(50 / 255f, back.curve("rgb")[1].y, 1e-3f)
        assertEquals(10 / 255f, back.curve("r")[0].y, 1e-3f)
    }

    @Test
    fun readsElementFormAndAbsoluteTemperature() {
        val xml = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/">
              <crs:Exposure2012>-1.00</crs:Exposure2012>
              <crs:Temperature>13008</crs:Temperature>
              <crs:Tint>+15</crs:Tint>
              <crs:HasCrop>True</crs:HasCrop><crs:CropTop>0.2</crs:CropTop>
            </rdf:Description></rdf:RDF></x:xmpmeta>"""
        val s = CrsXmp.read(xml)
        assertEquals(-1f, s[Params.EXPOSURE], 1e-4f)
        // 13008 K is one 1.2-octave step above 6504 K: slider +83.
        assertEquals(83.3f, s[Params.TEMP], 0.5f)
        assertEquals(10f, s[Params.TINT], 0.2f)
        assertEquals(0.2f, s[Params.CROP_TOP], 1e-4f)
    }

    @Test
    fun rotatedCropsAreNotExported() {
        val xml = CrsXmp.write(EditStack().with(Params.CROP_LEFT, 0.2f).with(Params.CROP_ROT90, 1f))
        assertTrue(!xml.contains("HasCrop"))
    }

    @Test(expected = Exception::class)
    fun rejectsDoctype() {
        CrsXmp.read("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">&e;</x:xmpmeta>")
    }

    private fun minimalJpeg(): ByteArray {
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 8, 'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        val dqt = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 3, 1)
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exif + dqt + sos
    }

    @Test
    fun embedsAndReadsBackEditStack() {
        val stack = lightroomish.copy(source = SourceRef(5, "abcd"))
        val jpeg = XmpWriter.insert(minimalJpeg(), stack, "Open Camera", "adjusted exposure")
        val back = XmpWriter.readStack(jpeg)
        assertNotNull(back)
        assertEquals(StackCodec.encode(stack), StackCodec.encode(back!!))
        // EXIF stays first, image data stays intact.
        assertEquals(0xE1.toByte(), jpeg[3])
        assertArrayEquals(minimalJpeg().copyOfRange(minimalJpeg().size - 8, minimalJpeg().size), jpeg.copyOfRange(jpeg.size - 8, jpeg.size))
        val xmp = XmpWriter.extract(jpeg)!!
        assertTrue(xmp.contains("xmp:CreatorTool=\"Open Camera\""))
        assertTrue(xmp.contains("photoshop:History=\"adjusted exposure\""))
    }

    @Test
    fun replacesExistingXmp() {
        val once = XmpWriter.insert(minimalJpeg(), EditStack().with(Params.EXPOSURE, 1f), "A", "h1")
        val twice = XmpWriter.insert(once, EditStack().with(Params.EXPOSURE, 2f), "B", "h2")
        val count = String(twice, Charsets.ISO_8859_1).split("http://ns.adobe.com/xap/1.0/\u0000").size - 1
        assertEquals(1, count)
        assertEquals(2f, XmpWriter.readStack(twice)!![Params.EXPOSURE])
    }

    @Test
    fun jpegWithoutXmpHasNoStack() {
        assertNull(XmpWriter.readStack(minimalJpeg()))
    }
}
