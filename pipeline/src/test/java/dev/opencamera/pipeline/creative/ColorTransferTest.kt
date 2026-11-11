/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import dev.opencamera.pipeline.looks.CubeParser
import dev.opencamera.pipeline.looks.CubeWriter
import dev.opencamera.pipeline.looks.LookMath
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.Lut3d
import dev.opencamera.pipeline.looks.ToneTable
import dev.opencamera.pipeline.looks.UserLookStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

class ColorTransferTest {
    private val size = 96

    /** Synthetic scene: sky gradient, ground, a few coloured objects and a skin-toned patch. */
    private fun scene(seed: Int): IntArray {
        val rnd = Random(seed)
        val px = IntArray(size * size)
        val blobs = List(8) {
            Triple(rnd.nextFloat() * size, rnd.nextFloat() * size, rnd.nextFloat() * 14 + 6) to
                floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
        }
        for (y in 0 until size) for (x in 0 until size) {
            val t = y / (size - 1f)
            var r: Float; var g: Float; var b: Float
            if (t < 0.5f) { r = 0.45f + 0.2f * t; g = 0.6f + 0.2f * t; b = 0.85f } else {
                r = 0.35f + 0.2f * (1 - t); g = 0.45f; b = 0.25f
            }
            for ((c, col) in blobs) {
                val (bx, by, br) = c
                if ((x - bx) * (x - bx) + (y - by) * (y - by) < br * br) { r = col[0]; g = col[1]; b = col[2] }
            }
            val n = (rnd.nextFloat() - 0.5f) * 0.04f
            px[y * size + x] = argb(r + n, g + n, b + n)
        }
        return px
    }

    /** A strong "teal and orange" grade with lifted blacks: the reference style. */
    private fun grade(px: IntArray) = IntArray(px.size) { i ->
        val p = px[i]
        val r = ((p shr 16) and 0xff) / 255f
        val g = ((p shr 8) and 0xff) / 255f
        val b = (p and 0xff) / 255f
        val l = 0.3f * r + 0.6f * g + 0.1f * b
        val s = l.pow(0.8f)
        argb(
            0.1f + 0.85f * (s + (r - l) * 0.6f + 0.12f * l),
            0.08f + 0.85f * (s + (g - l) * 0.6f + 0.02f),
            0.12f + 0.8f * (s + (b - l) * 0.6f + 0.12f * (1 - l)),
        )
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xff shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)

    private fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private fun applyLut(lut: Lut3d, px: IntArray): IntArray {
        val params = LookParams(lut, ToneTable.build(listOf(0f to 0f, 1f to 1f), 0f), 1f, 0f, 1f, 0f, 0.5f)
        return px.copyOf().also { LookMath.applyArgb(params, it, size, size) }
    }

    private fun reduction(method: ColorTransferMethod, sourceSeed: Int, refSeed: Int): Float {
        val source = scene(sourceSeed)
        val reference = grade(scene(refSeed))
        val result = ColorTransfer.build(
            ColorSamples.fromArgb(source), ColorSamples.fromArgb(reference), method, skinProtection = 0f,
        )
        assertEquals(method, result.method)
        val out = applyLut(result.lut, source)
        val before = ColorMetrics.histogramDeltaE(source, reference)
        val after = ColorMetrics.histogramDeltaE(out, reference)
        return 1f - after / before
    }

    @Test
    fun everyMethodHalvesHistogramDeltaE() {
        for (method in ColorTransferMethod.entries) {
            for ((s, r) in listOf(1 to 2, 3 to 4, 5 to 6)) {
                val red = reduction(method, s, r)
                println("$method scene $s -> $r: Delta E histogram reduction ${"%.0f".format(red * 100)}%")
                assertTrue("$method $s->$r reduced only ${red * 100}%", red >= 0.5f)
            }
        }
    }

    @Test
    fun slicedOtIsAtLeastAsCloseAsReinhard() {
        val source = scene(7)
        val reference = grade(scene(8))
        fun after(m: ColorTransferMethod) = ColorMetrics.histogramDeltaE(
            applyLut(ColorTransfer.build(ColorSamples.fromArgb(source), ColorSamples.fromArgb(reference), m, 0f).lut, source),
            reference,
        )
        assertTrue(after(ColorTransferMethod.SLICED_OT) <= after(ColorTransferMethod.REINHARD) * 1.05f)
    }

    @Test
    fun skinProtectionKeepsSkinCloserToOriginal() {
        val source = scene(9)
        val reference = grade(scene(10))
        fun shift(protect: Float): Float {
            val lut = ColorTransfer.build(
                ColorSamples.fromArgb(source), ColorSamples.fromArgb(reference), ColorTransferMethod.SLICED_OT, protect,
            ).lut
            val out = FloatArray(3)
            LookMath.sampleLut(lut, 0.85f, 0.62f, 0.5f, out)
            return abs(out[0] - 0.85f) + abs(out[1] - 0.62f) + abs(out[2] - 0.5f)
        }
        val lab = FloatArray(3)
        ColorSpaces.srgbToOklab(0.85f, 0.62f, 0.5f, lab)
        assertTrue(ColorTransfer.skinWeight(lab[0], lab[1], lab[2]) > 0.5f)
        assertTrue(shift(1f) < shift(0f) * 0.5f)
    }

    @Test
    fun degenerateInputsFallBackWithoutThrowing() {
        val flat = ColorSamples.fromArgb(IntArray(500) { argb(0.5f, 0.5f, 0.5f) })
        val normal = ColorSamples.fromArgb(scene(11))
        val tiny = ColorSamples.fromArgb(IntArray(3) { argb(0.2f, 0.3f, 0.4f) })
        val empty = ColorSamples(FloatArray(0))
        val nan = ColorSamples(FloatArray(300) { Float.NaN })
        for (m in ColorTransferMethod.entries) {
            for ((a, b) in listOf(flat to normal, normal to flat, tiny to normal, empty to empty, nan to normal)) {
                val r = ColorTransfer.build(a, b, m)
                assertEquals(33, r.lut.size)
                assertTrue(r.lut.data.all { it.isFinite() && it in 0f..1f })
            }
        }
        // Too few samples: identity.
        val id = ColorTransfer.build(tiny, normal, ColorTransferMethod.SLICED_OT).lut
        val ref = Lut3d.identity(33)
        var maxErr = 0f
        for (i in id.data.indices) maxErr = max(maxErr, abs(id.data[i] - ref.data[i]))
        assertTrue(maxErr < 1e-5f)
    }

    @Test
    fun identicalImagesGiveNearIdentityLut() {
        val px = scene(12)
        val lut = ColorTransfer.build(ColorSamples.fromArgb(px), ColorSamples.fromArgb(px), ColorTransferMethod.SLICED_OT, 0f).lut
        val out = applyLut(lut, px)
        assertTrue(ColorMetrics.meanDeltaE(out, px) < 2f)
    }

    @Test
    fun cubeExportRoundTrips() {
        val lut = ColorTransfer.build(
            ColorSamples.fromArgb(scene(13)), ColorSamples.fromArgb(grade(scene(14))), ColorTransferMethod.HISTOGRAM,
        ).lut
        val text = CubeWriter.write(lut, "Test \"look\"")
        assertTrue(text.startsWith("TITLE"))
        assertTrue(text.contains("LUT_3D_SIZE 33"))
        val back = CubeParser.parse(text)
        assertEquals(lut.size, back.size)
        var maxErr = 0f
        for (i in lut.data.indices) maxErr = max(maxErr, abs(lut.data[i] - back.data[i]))
        assertTrue(maxErr < 1e-5f)
    }

    @Test
    fun userLookStoreSavesListsAndDeletes() {
        val dir = Files.createTempDirectory("looks").toFile()
        try {
            val store = UserLookStore(dir)
            val d = store.save("Sunset match", "from IMG_1", Lut3d.identity(17), 0.8f)
            assertTrue(d.id.startsWith(UserLookStore.PREFIX))
            val listed = store.list()
            assertEquals(1, listed.size)
            assertEquals("Sunset match", listed[0].name)
            assertEquals(0.8f, listed[0].intensity, 1e-4f)
            assertNotNull(store.lut(listed[0]))
            // A broken look is skipped, not fatal.
            java.io.File(dir, "user-broken").apply { mkdirs(); resolve("look.json").writeText("{") }
            assertEquals(1, store.list().size)
            assertTrue(store.delete(d.id))
            assertEquals(0, store.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oklabRoundTrip() {
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        for (r in 0..4) for (g in 0..4) for (b in 0..4) {
            ColorSpaces.srgbToOklab(r / 4f, g / 4f, b / 4f, lab)
            ColorSpaces.oklabToSrgb(lab[0], lab[1], lab[2], rgb)
            assertEquals(r / 4f, rgb[0], 1e-3f)
            assertEquals(g / 4f, rgb[1], 1e-3f)
            assertEquals(b / 4f, rgb[2], 1e-3f)
        }
    }
}
