/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

class LookTest {
    private fun identityParams(size: Int = 17, intensity: Float = 1f) = LookParams(
        Lut3d.identity(size), ToneTable.build(listOf(0f to 0f, 1f to 1f), 0f), intensity, 0f, 1f, 0f, 0.5f,
    )

    private fun cube(size: Int, body: String) = "TITLE \"t\"\nLUT_3D_SIZE $size\n$body"

    @Test
    fun parsesCubeInRedFastestOrder() {
        val lut = CubeParser.parse(
            cube(2, "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n")
        )
        assertEquals(2, lut.size)
        val out = FloatArray(3)
        LookMath.sampleLut(lut, 0.25f, 0.5f, 0.75f, out)
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(0.75f, out[2], 1e-6f)
    }

    @Test
    fun rejectsBrokenCubes() {
        val bad = mapOf(
            "missing size" to "0 0 0\n",
            "too few entries" to cube(2, "0 0 0\n"),
            "bad number" to cube(2, "0 0 x\n" + "0 0 0\n".repeat(7)),
            "two values" to cube(2, "0 0\n"),
            "1D" to "LUT_1D_SIZE 16\n",
            "domain" to "LUT_3D_SIZE 2\nDOMAIN_MAX 2 2 2\n",
            "size range" to "LUT_3D_SIZE 1\n",
            "too many" to cube(2, "0 0 0\n".repeat(9)),
        )
        for ((name, text) in bad) {
            try {
                CubeParser.parse(text)
                fail("accepted $name")
            } catch (e: CubeParseException) {
                assertTrue(name, e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun identityLookLeavesImageUnchanged() {
        val rnd = Random(1)
        val w = 37
        val h = 23
        val pixels = IntArray(w * h) { 0xff000000.toInt() or rnd.nextInt(0x1000000) }
        val copy = pixels.copyOf()
        LookMath.applyArgb(identityParams(), pixels, w, h)
        assertTrue(pixels.contentEquals(copy))
    }

    @Test
    fun tetrahedralReproducesAffineLut() {
        // Any affine colour transform is represented exactly by tetrahedral interpolation.
        val n = 9
        val data = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val (fr, fg, fb) = Triple(r / 8f, g / 8f, b / 8f)
            data[i++] = 0.1f + 0.5f * fr + 0.2f * fg
            data[i++] = 0.3f * fg + 0.1f * fb
            data[i++] = 0.6f * fb + 0.2f * fr
        }
        val lut = Lut3d(n, data)
        val out = FloatArray(3)
        val rnd = Random(7)
        repeat(500) {
            val r = rnd.nextFloat(); val g = rnd.nextFloat(); val b = rnd.nextFloat()
            LookMath.sampleLut(lut, r, g, b, out)
            assertEquals(0.1f + 0.5f * r + 0.2f * g, out[0], 1e-5f)
            assertEquals(0.3f * g + 0.1f * b, out[1], 1e-5f)
            assertEquals(0.6f * b + 0.2f * r, out[2], 1e-5f)
        }
    }

    @Test
    fun zeroIntensityDisablesEverything() {
        val base = LookDescriptor.parse(File(LOOKS, "high-contrast-mono/look.json").readText())
        val lut = CubeParser.parse(File(LOOKS, "high-contrast-mono/lut.cube").readText())
        val params = LookParams.of(base, lut, intensity = 0f)
        val pixels = IntArray(64 * 64) { 0xff000000.toInt() or (it * 977 and 0xffffff) }
        val copy = pixels.copyOf()
        LookMath.applyArgb(params, pixels, 64, 64)
        assertTrue(pixels.contentEquals(copy))
    }

    @Test
    fun toneTableIsMonotoneAndRolloffLowersWhite() {
        val curve = listOf(0f to 0f, 0.25f to 0.2f, 0.75f to 0.82f, 1f to 1f)
        val t = ToneTable.build(curve, 0f)
        assertEquals(0f, t.first(), 1e-6f)
        assertEquals(1f, t.last(), 1e-6f)
        assertTrue(t.toList().zipWithNext().all { (a, b) -> b >= a })
        val rolled = ToneTable.build(curve, 1f)
        assertTrue(rolled.last() < 1f)
        assertEquals(t[100], rolled[100], 1e-6f)
    }

    @Test
    fun builtInLooksParseAndStayInRange() {
        val ids = Regex("\"([a-z-]+)\"").findAll(File(LOOKS, "index.json").readText())
            .map { it.groupValues[1] }.filter { it != "looks" }.toList()
        assertTrue("expected 12+ looks, got $ids", ids.size >= 12)
        for (id in ids) {
            val d = LookDescriptor.parse(File(LOOKS, "$id/look.json").readText())
            assertEquals(id, d.id)
            val lut = CubeParser.parse(File(LOOKS, "$id/${d.lutFile}").readText())
            assertEquals(33, lut.size)
            assertTrue(id, lut.data.all { it in 0f..1f })
            // Every look keeps black near black and white near white.
            val out = FloatArray(3)
            LookMath.sampleLut(lut, 0f, 0f, 0f, out)
            assertTrue("$id black ${out.toList()}", out.all { it < 0.05f })
            LookMath.sampleLut(lut, 1f, 1f, 1f, out)
            assertTrue("$id white ${out.toList()}", out.all { it > 0.9f })
        }
    }

    @Test
    fun grainAndVignetteAreDeterministic() {
        val d = LookDescriptor.parse(File(LOOKS, "classic-ccd-plus/look.json").readText())
        val params = LookParams.of(d, Lut3d.identity(9))
        val a = IntArray(40 * 30) { 0xff808080.toInt() }
        val b = a.copyOf()
        LookMath.applyArgb(params, a, 40, 30)
        LookMath.applyArgb(params, b, 40, 30)
        assertTrue(a.contentEquals(b))
        // Corners are darker than the centre.
        assertTrue((a[0] and 0xff) < (a[15 * 40 + 20] and 0xff))
    }

    private companion object {
        val LOOKS = File("../app/src/main/assets/looks")
    }
}
