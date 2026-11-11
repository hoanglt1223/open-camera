/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import dev.opencamera.app.develop.auto.AutoAdjust
import dev.opencamera.app.develop.mask.MaskRaster
import dev.opencamera.app.develop.model.BrushShape
import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.LinearShape
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.model.MaskComponent
import dev.opencamera.app.develop.model.MaskOp
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.model.RadialShape
import dev.opencamera.app.develop.model.Stroke
import dev.opencamera.app.develop.render.ColorMath
import dev.opencamera.app.develop.render.CpuDevelop
import dev.opencamera.app.develop.render.DevelopKernel
import dev.opencamera.app.develop.render.DevelopUniforms
import dev.opencamera.app.develop.render.Geometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class DevelopMathTest {
    private fun shade(stack: EditStack, r: Float, g: Float, b: Float, blur: FloatArray? = null): FloatArray {
        val u = DevelopUniforms.from(stack, null)
        val p = DevelopKernel.Pixel()
        val lin = floatArrayOf(ColorMath.srgbToLinear(r), ColorMath.srgbToLinear(g), ColorMath.srgbToLinear(b))
        lin.copyInto(p.lin)
        (blur ?: lin).copyInto(p.bs); (blur ?: lin).copyInto(p.bm)
        val bl = blur ?: lin
        p.bl[0] = bl[0]; p.bl[1] = bl[1]; p.bl[2] = bl[2]; p.bl[3] = minOf(bl[0], bl[1], bl[2])
        DevelopKernel.shade(u, p, 0.5f, 0.5f, 100f, 100f)
        return p.out.copyOf()
    }

    @Test
    fun identityStackReproducesTheInput() {
        val rnd = Random(3)
        repeat(500) {
            val c = floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat())
            val o = shade(EditStack(), c[0], c[1], c[2])
            for (k in 0..2) assertEquals(c[k], o[k], 0.5f / 255f)
        }
    }

    @Test
    fun exposureDoublesLinearLight() {
        val o = shade(EditStack().with(Params.EXPOSURE, 1f), 0.4f, 0.4f, 0.4f)
        val expected = ColorMath.linearToSrgb(ColorMath.srgbToLinear(0.4f) * 2f)
        assertEquals(expected, o[1], 2f / 255f)
    }

    @Test
    fun whiteBalanceIsIdentityAtZeroAndWarmsWhenPositive() {
        val m = ColorMath.whiteBalance(0f, 0f).m
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        for (i in 0..8) assertEquals(id[i], m[i], 1e-4f)
        val warm = shade(EditStack().with(Params.TEMP, 50f), 0.5f, 0.5f, 0.5f)
        assertTrue("warm R>B", warm[0] > warm[2] + 0.03f)
        val magenta = shade(EditStack().with(Params.TINT, 50f), 0.5f, 0.5f, 0.5f)
        assertTrue("tint lowers green", magenta[1] < magenta[0] && magenta[1] < magenta[2])
    }

    @Test
    fun saturationMinusHundredIsGrey() {
        val o = shade(EditStack().with(Params.SATURATION, -100f), 0.8f, 0.3f, 0.2f)
        assertEquals(o[0], o[1], 1e-3f)
        assertEquals(o[1], o[2], 1e-3f)
    }

    @Test
    fun contrastSpreadsAroundMidGrey() {
        val dark = shade(EditStack().with(Params.CONTRAST, 60f), 0.2f, 0.2f, 0.2f)[0]
        val light = shade(EditStack().with(Params.CONTRAST, 60f), 0.8f, 0.8f, 0.8f)[0]
        assertTrue(dark < 0.2f && light > 0.8f)
    }

    @Test
    fun hslBandWeightsArePartitionOfUnity() {
        var h = 0f
        while (h < 360f) {
            var sum = 0f
            DevelopKernel.bandWeights(h) { _, w -> sum += w }
            assertEquals(1f, sum, 1e-5f)
            h += 0.7f
        }
        // Pure red belongs to the red band.
        var red = 0f
        DevelopKernel.bandWeights(0f) { b, w -> if (b == 0) red = w }
        assertEquals(1f, red, 1e-5f)
    }

    @Test
    fun hslHueShiftOnlyMovesTheTargetedColours() {
        val s = EditStack().with(Params.hsl("blue", "hue"), 100f)
        val blue = shade(s, 0.1f, 0.2f, 0.9f)
        val red = shade(s, 0.9f, 0.1f, 0.1f)
        assertTrue(abs(ColorMath.hue(blue[0], blue[1], blue[2]) - ColorMath.hue(0.1f, 0.2f, 0.9f)) > 10f)
        assertEquals(0.9f, red[0], 1f / 255f)
    }

    @Test
    fun curveTableIsMonotoneEvenForExtremeParametricValues() {
        val s = EditStack().with(Params.PC_SHADOWS, 100f).with(Params.PC_DARKS, -100f).with(Params.PC_LIGHTS, 100f).with(Params.PC_HIGHLIGHTS, -100f)
            .withCurve("rgb", listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.6f), CurvePoint(0.35f, 0.62f), CurvePoint(1f, 1f)))
        val t = DevelopUniforms.curveTable(s)
        for (c in 0..2) for (i in 1 until 256) assertTrue("ch $c at $i", t[i * 4 + c] >= t[(i - 1) * 4 + c] - 1e-6f)
        assertTrue(DevelopUniforms.from(EditStack(), null).curveIsIdentity)
    }

    @Test
    fun gradingTintsShadowsNotHighlights() {
        val s = EditStack().with(Params.grade("shadows", "hue"), 240f).with(Params.grade("shadows", "sat"), 100f)
        val dark = shade(s, 0.1f, 0.1f, 0.1f)
        val bright = shade(s, 0.95f, 0.95f, 0.95f)
        assertTrue(dark[2] > dark[0] + 0.02f)
        assertEquals(bright[0], bright[2], 0.01f)
    }

    @Test
    fun negativeVignetteDarkensCornersOnly() {
        val u = DevelopUniforms.from(EditStack().with(Params.VIGNETTE, -80f), null)
        fun at(x: Float, y: Float): Float {
            val p = DevelopKernel.Pixel()
            val l = ColorMath.srgbToLinear(0.5f)
            for (k in 0..2) { p.lin[k] = l; p.bs[k] = l; p.bm[k] = l; p.bl[k] = l }
            DevelopKernel.shade(u, p, x, y, 400f, 300f)
            return p.out[0]
        }
        assertEquals(0.5f, at(0.5f, 0.5f), 1f / 255f)
        assertTrue(at(0.01f, 0.01f) < 0.3f)
    }

    @Test
    fun grainIsDeterministicAndResolutionRelative() {
        val a = DevelopKernel.grainNoise(123.4f, 56.7f, 3f, 0.5f)
        assertEquals(a, DevelopKernel.grainNoise(123.4f, 56.7f, 3f, 0.5f))
        // Same relative position and relative cell size give the same noise at 2x resolution.
        assertEquals(DevelopKernel.grainNoise(10.5f, 20.5f, 2f, 1f), DevelopKernel.grainNoise(21f, 41f, 4f, 1f), 1e-6f)
    }

    @Test
    fun clarityRaisesLocalContrast() {
        val blur = floatArrayOf(0.18f, 0.18f, 0.18f)
        val plain = shade(EditStack(), 0.6f, 0.6f, 0.6f, blur)[0]
        val clear = shade(EditStack().with(Params.CLARITY, 80f), 0.6f, 0.6f, 0.6f, blur)[0]
        assertTrue(clear > plain)
    }

    @Test
    fun highlightsSliderRecoversBrightAreas() {
        val down = shade(EditStack().with(Params.HIGHLIGHTS, -100f), 0.9f, 0.9f, 0.9f)[0]
        assertTrue(down < 0.88f)
        val shadowsUp = shade(EditStack().with(Params.SHADOWS, 100f), 0.08f, 0.08f, 0.08f)[0]
        assertTrue(shadowsUp > 0.1f)
    }

    @Test
    fun geometryIdentityAndInverse() {
        val f = Geometry.frame(EditStack(), 400, 300)
        assertEquals(400, f.outWidth); assertEquals(300, f.outHeight)
        val p = FloatArray(2)
        f.outputToSource.map(0.25f, 0.75f, p)
        assertEquals(0.25f, p[0], 1e-5f); assertEquals(0.75f, p[1], 1e-5f)
        val turned = Geometry.frame(EditStack().with(Params.CROP_ROT90, 1f), 400, 300)
        assertEquals(300, turned.outWidth); assertEquals(400, turned.outHeight)
        // Output top-left after a clockwise quarter turn comes from the source bottom-left.
        turned.outputToSource.map(0f, 0f, p)
        assertEquals(0f, p[0], 1e-4f); assertEquals(1f, p[1], 1e-4f)
        val m = Geometry.frame(EditStack().with(Params.CROP_ANGLE, 7f).with(Params.PERSPECTIVE_V, 30f), 400, 300).outputToSource
        val inv = m.inverse()
        val q = FloatArray(2)
        m.map(0.3f, 0.6f, q); inv.map(q[0], q[1], p)
        assertEquals(0.3f, p[0], 1e-4f); assertEquals(0.6f, p[1], 1e-4f)
    }

    @Test
    fun straightenAutoCropsAwayEmptyCorners() {
        val s = Geometry.fitCrop(EditStack().with(Params.CROP_ANGLE, 10f), 4000, 3000)
        assertTrue(Geometry.inside(s, 4000, 3000))
        assertTrue(s[Params.CROP_RIGHT] - s[Params.CROP_LEFT] < 1f)
        val sq = Geometry.applyAspect(EditStack(), 4000, 3000, 1f)
        val f = Geometry.frame(sq, 4000, 3000)
        assertEquals(f.outWidth.toFloat(), f.outHeight.toFloat(), 2f)
    }

    @Test
    fun autoWhiteBalanceNeutralizesACast() {
        // A bluish scene: gray-world says warm it up.
        val rnd = Random(1)
        val px = IntArray(20000) {
            val l = 60 + rnd.nextInt(120)
            (0xff shl 24) or ((l * 0.8f).toInt() shl 16) or (l shl 8) or minOf(255, (l * 1.25f).toInt())
        }
        val wb = AutoAdjust.whiteBalance(AutoAdjust.samples(px))
        assertTrue("temp ${wb[Params.TEMP]}", wb.getValue(Params.TEMP) > 5f)
        val after = shade(EditStack().withAll(wb), 0.5f * 0.8f / 0.8f * 0.8f, 0.5f, minOf(1f, 0.5f * 1.25f))
        assertTrue(abs(after[0] - after[2]) < abs(0.4f - 0.625f))
    }

    @Test
    fun autoToneBrightensADarkPhoto() {
        val rnd = Random(2)
        val px = IntArray(20000) { val l = 5 + rnd.nextInt(50); (0xff shl 24) or (l shl 16) or (l shl 8) or l }
        val t = AutoAdjust.tone(AutoAdjust.samples(px))
        assertTrue(t.getValue(Params.EXPOSURE) > 0.5f)
    }

    @Test
    fun autoStraightenFindsATiltedHorizon() {
        val w = 640; val h = 480
        val angle = Math.toRadians(4.0)
        val px = IntArray(w * h) { i ->
            val x = i % w - w / 2.0; val y = i / w - h / 2.0
            // Anti-aliased edge, as a lens renders it.
            val d = ((y - x * kotlin.math.tan(angle)) * kotlin.math.cos(angle)).coerceIn(-0.5, 0.5) + 0.5
            val v = (200 - 140 * d).toInt()
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        val a = AutoAdjust.straighten(px, w, h)
        assertEquals(-4f, a, 0.5f)
    }

    @Test
    fun maskShapesAndCombination() {
        val w = 100; val h = 80
        val radial = MaskRaster.radial(RadialShape(0.5f, 0.5f, 0.2f, 0.2f, 0f, 0.5f), w, h)
        assertEquals(1f, radial[40 * w + 50], 1e-3f)
        assertEquals(0f, radial[0], 1e-3f)
        val linear = MaskRaster.linear(LinearShape(0f, 0f, 0f, 1f), w, h)
        assertTrue(linear[0] > 0.99f && linear[(h - 1) * w] < 0.01f)
        val brush = MaskRaster.brush(listOf(Stroke(floatArrayOf(0.2f, 0.5f, 0.8f, 0.5f), 0.05f, 0f)), w, h)
        assertEquals(1f, brush[40 * w + 50], 1e-3f)
        assertEquals(0f, brush[5 * w + 50], 1e-3f)
        val m = LocalMask("m", "m", listOf(
            MaskComponent(MaskOp.ADD, RadialShape(0.5f, 0.5f, 0.3f, 0.3f, 0f, 0f)),
            MaskComponent(MaskOp.SUBTRACT, BrushShape(listOf(Stroke(floatArrayOf(0.5f, 0.5f), 0.05f, 0f)))),
        ))
        val cov = MaskRaster.render(m, w, h, null) { null }
        assertEquals(0f, cov[40 * w + 50], 1e-3f)
        assertEquals(1f, cov[40 * w + 30], 1e-3f)
        val inter = MaskRaster.render(m.copy(components = listOf(m.components[0], m.components[1].copy(op = MaskOp.INTERSECT, invert = true))), w, h, null) { null }
        assertEquals(0f, inter[40 * w + 50], 1e-3f)
    }

    @Test
    fun guidedFilterKeepsConstantMasks() {
        val w = 32; val h = 32
        val guide = FloatArray(w * h) { (it % w) / w.toFloat() }
        val out = MaskRaster.guidedFilter(guide, FloatArray(w * h) { 0.7f }, w, h, 4, 1e-3f)
        for (v in out) assertEquals(0.7f, v, 1e-3f)
    }

    @Test
    fun localMaskOnlyChangesCoveredPixels() {
        val s = EditStack().copy(masks = listOf(LocalMask("m", "m", emptyList(), mapOf(Params.EXPOSURE to 1f))))
        val u = DevelopUniforms.from(s, null)
        val p = DevelopKernel.Pixel()
        val l = ColorMath.srgbToLinear(0.4f)
        for (k in 0..2) { p.lin[k] = l; p.bs[k] = l; p.bm[k] = l; p.bl[k] = l }
        DevelopKernel.shade(u, p, 0.5f, 0.5f, 10f, 10f)
        assertEquals(0.4f, p.out[0], 1f / 255f)
        p.mask[0] = 1f
        DevelopKernel.shade(u, p, 0.5f, 0.5f, 10f, 10f)
        assertEquals(ColorMath.linearToSrgb(l * 2f), p.out[0], 2f / 255f)
    }

    @Test
    fun cpuRendererMatchesTheKernelAndHonoursCrop() {
        val w = 64; val h = 48
        val px = IntArray(w * h) { i -> val v = (i % w) * 4; (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        val prep = CpuDevelop.prepare(px, w, h)
        val id = CpuDevelop.render(DevelopUniforms.from(EditStack(), null), prep, Geometry.frame(EditStack(), w, h), w, h, emptyList(), threads = 2)
        var maxErr = 0
        for (i in px.indices) maxErr = max(maxErr, abs((id[i] and 255) - (px[i] and 255)))
        assertTrue("identity error $maxErr", maxErr <= 2)
        val crop = EditStack().with(Params.CROP_LEFT, 0.5f)
        val f = Geometry.frame(crop, w, h)
        assertEquals(32, f.outWidth)
        val out = CpuDevelop.render(DevelopUniforms.from(crop, null), prep, f, f.outWidth, f.outHeight, emptyList(), threads = 2)
        assertEquals(px[32] and 255, out[0] and 255, 3)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) = assertTrue("$expected vs $actual", abs(expected - actual) <= tolerance)
}
