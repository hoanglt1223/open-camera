/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import dev.opencamera.pipeline.ai.AiOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RetouchStackTest {
    private val params = RetouchParams(
        strength = 0.4f,
        method = "migan",
        taps = listOf(NPoint(0.25f, 0.75f)),
        strokes = listOf(Stroke(listOf(NPoint(0.1f, 0.2f), NPoint(0.3f, 0.4f)), 0.02f), Stroke(listOf(NPoint(0.5f, 0.5f)), 0.01f, erase = true)),
        values = mapOf("slim" to 0.5f, "angle" to -2.5f),
    )

    @Test
    fun paramsRoundTrip() {
        assertEquals(params, RetouchParams.fromJson(params.toJson()))
        assertEquals(RetouchParams(), RetouchParams.fromJson(RetouchParams().toJson()))
    }

    @Test
    fun undoRedoAndBranching() {
        val s = RetouchStack()
        s.push(RetouchOp("skin", params))
        s.push(RetouchOp("teeth", RetouchParams(strength = 0.5f)))
        assertEquals("teeth", s.undo()?.tool)
        assertTrue(s.canRedo)
        s.push(RetouchOp("eyes", RetouchParams()))
        assertFalse("a new edit drops the undone one", s.canRedo)
        assertEquals(listOf("skin", "eyes"), s.active.map { it.tool })
        assertEquals("eyes", s.undo()?.tool)
        assertEquals("skin", s.undo()?.tool)
        assertNull(s.undo())
        assertEquals("skin", s.redo()?.tool)
    }

    @Test
    fun jsonKeepsAppliedCursor() {
        val s = RetouchStack()
        s.push(RetouchOp("skin", params))
        s.push(RetouchOp("geometry", RetouchParams(values = mapOf("angle" to 1f))))
        s.undo()
        val back = RetouchStack.fromJson(s.toJson())!!
        assertEquals(1, back.applied)
        assertEquals(s.active, back.active)
        assertTrue(back.canRedo)
    }

    @Test
    fun rejectsBadOrNewerJson() {
        assertNull(RetouchStack.fromJson("{"))
        assertNull(RetouchStack.fromJson(null))
        assertNull(RetouchStack.fromJson("""{"version":99,"ops":[]}"""))
    }

    @Test
    fun aiOpsCarriesRetouchStack() {
        val s = RetouchStack().apply { push(RetouchOp("skin", params)) }
        val ops = AiOps(keepSource = true, retouch = s.toJson())
        val back = AiOps.fromJson(ops.toJson())!!
        assertFalse(back.isEmpty)
        assertEquals(s.active, RetouchStack.fromJson(back.retouch)!!.active)
    }
}

class InpaintPlanTest {
    private val all = InpaintMethod.entries.toSet()

    @Test
    fun smallSpotsGoToTelea() {
        assertEquals(listOf(InpaintMethod.TELEA), InpaintPlan.chain(null, 0.005f, all))
    }

    @Test
    fun defaultChainFallsBackWhenModelMissing() {
        assertEquals(listOf(InpaintMethod.MIGAN, InpaintMethod.PATCHMATCH, InpaintMethod.TELEA), InpaintPlan.chain(null, 0.05f, all))
        val noModel = all - InpaintMethod.MIGAN - InpaintMethod.LAMA
        assertEquals(listOf(InpaintMethod.PATCHMATCH, InpaintMethod.TELEA), InpaintPlan.chain(null, 0.05f, noModel))
        assertEquals(listOf(InpaintMethod.TELEA), InpaintPlan.chain(InpaintMethod.MIGAN, 0.05f, setOf(InpaintMethod.TELEA)))
        assertTrue(InpaintPlan.chain(null, 0.05f, emptySet()).isEmpty())
    }

    @Test
    fun lamaNotDownloadedUsesMigan() {
        assertEquals(InpaintMethod.MIGAN, InpaintPlan.chain(InpaintMethod.LAMA, 0.2f, all - InpaintMethod.LAMA).first())
        assertEquals(InpaintMethod.LAMA, InpaintPlan.chain(InpaintMethod.LAMA, 0.2f, all).first())
    }
}

class ReshapeMathTest {
    @Test
    fun strengthsAreCapped() {
        assertEquals(ReshapeMath.SLIM_MAX, ReshapeMath.clampStrength("slim", 5f), 0f)
        assertEquals(ReshapeMath.EYES_MAX, ReshapeMath.clampStrength("eyes", 2f), 0f)
        assertEquals(0f, ReshapeMath.clampStrength("nose", -1f), 0f)
        assertTrue(1f / (1f - ReshapeMath.EYES_MAX) <= 1.10f + 1e-4f)
    }

    @Test
    fun bulgeMagnifiesAtMostTenPercent() {
        val f = ReshapeMath.Field(201, 201, 201, 201)
        ReshapeMath.bulge(f, 100f, 100f, 60f, ReshapeMath.clampStrength("eyes", 1f))
        // Output pixel 1 px from the centre samples the source at (1 - s): magnification 1 / (1 - s).
        val (dx, _) = f.at(101f, 100f)
        val sampled = 1f + dx
        assertTrue(1f / sampled <= 1.101f)
        assertEquals(0f, f.at(170f, 100f).first, 1e-6f)
    }

    @Test
    fun anchorsKeepFarAreasStill() {
        val f = ReshapeMath.Field(101, 101, 1001, 1001)
        ReshapeMath.interpolate(f, listOf(ReshapeMath.Control(500f, 500f, 20f, 0f)), 40f)
        assertEquals(0f, f.at(900f, 900f).first, 1e-3f)
        assertTrue(abs(f.at(520f, 500f).first) > 5f)
    }

    @Test
    fun bendMeasuresCurvature() {
        val f = ReshapeMath.Field(51, 51, 501, 501)
        assertEquals(0f, ReshapeMath.bend(f, 0f, 250f, 500f, 250f), 0f)
        ReshapeMath.bulge(f, 250f, 260f, 100f, 0.09f)
        assertTrue(ReshapeMath.bend(f, 0f, 250f, 500f, 250f) > 0.1f)
    }
}

class GeometryMathTest {
    @Test
    fun tiltFromNearlyLevelLines() {
        val lines = listOf(
            floatArrayOf(0f, 100f, 1000f, 135f), // about +2 deg
            floatArrayOf(0f, 400f, 800f, 428f),
            floatArrayOf(300f, 0f, 265f, 1000f), // near vertical, same rotation
        )
        val t = GeometryMath.tilt(lines, 100f)!!
        assertEquals(2f, t, 0.3f)
        assertNull(GeometryMath.tilt(listOf(floatArrayOf(0f, 0f, 10f, 0f)), 100f))
        assertEquals(-3f, GeometryMath.deviation(87f), 1e-4f)
    }

    @Test
    fun verticalLinesConvergingUpGivePositiveKeystone() {
        // Two lines meeting 1000 px above a 1000 x 1000 frame.
        val lines = listOf(floatArrayOf(100f, 1000f, 300f, 500f), floatArrayOf(900f, 1000f, 700f, 500f))
        val vp = GeometryMath.vanishingPoint(lines)!!
        assertEquals(500f, vp.first, 1f)
        assertEquals(0f, vp.second, 1f)
        val parallel = listOf(floatArrayOf(100f, 1000f, 100f, 0f), floatArrayOf(900f, 1000f, 900f, 0f))
        assertEquals(0f, GeometryMath.verticalKeystone(parallel, 1000, 1000), 0f)
        val lines2 = listOf(floatArrayOf(100f, 1000f, 200f, 0f), floatArrayOf(900f, 1000f, 800f, 0f))
        assertTrue(GeometryMath.verticalKeystone(lines2, 1000, 1000) > 0f)
    }

    @Test
    fun homographyMapsCorners() {
        val src = GeometryMath.keystoneQuad(0.5f, 0f, 1000, 800)
        val dst = floatArrayOf(0f, 0f, 1000f, 0f, 1000f, 800f, 0f, 800f)
        val h = GeometryMath.homography(src, dst)
        for (i in 0 until 4) {
            val (x, y) = GeometryMath.map(h, src[2 * i], src[2 * i + 1])
            assertEquals(dst[2 * i], x, 0.01f)
            assertEquals(dst[2 * i + 1], y, 0.01f)
        }
    }

    @Test
    fun transformIdentityAndRotationCrop() {
        val (m, rect) = GeometryMath.transform(400, 300, 0f, 0f, 0f)
        assertEquals(400f, rect[2], 0.5f)
        assertEquals(300f, rect[3], 0.5f)
        val (x, y) = GeometryMath.map(m, 10f, 20f)
        assertEquals(10f, x, 0.5f)
        assertEquals(20f, y, 0.5f)
        val (_, rotated) = GeometryMath.transform(400, 300, 5f, 0f, 0f)
        assertTrue(rotated[2] < 400f && rotated[2] > 300f)
        assertEquals(4f / 3f, rotated[2] / rotated[3], 0.01f)
    }
}

class WhiteBalanceMathTest {
    private fun px(r: Int, g: Int, b: Int, n: Int = 400) = IntArray(n) { (0xff shl 24) or (r shl 16) or (g shl 8) or b }

    @Test
    fun grayWorldNeutralizesCast() {
        val gains = WhiteBalanceMath.grayWorld(px(160, 128, 96))!!
        val lr = WhiteBalanceMath.linear(160) * gains[0]
        val lg = WhiteBalanceMath.linear(128) * gains[1]
        val lb = WhiteBalanceMath.linear(96) * gains[2]
        assertEquals(lr, lg, 1e-3f)
        assertEquals(lg, lb, 1e-3f)
    }

    @Test
    fun neutralImageNeedsNoGain() {
        val g = WhiteBalanceMath.grayWorld(px(120, 120, 120))!!
        g.forEach { assertEquals(1f, it, 1e-3f) }
        val lut = WhiteBalanceMath.luts(floatArrayOf(2f, 1f, 0.5f), 0f)
        for (i in 0..255) assertEquals(i, lut[0][i])
    }

    @Test
    fun gainsAreClampedAndDegenerateInputRejected() {
        val g = WhiteBalanceMath.gains(0.01f, 1f, 1f)!!
        assertEquals(2.5f, g[0], 0f)
        assertNull(WhiteBalanceMath.gains(0f, 1f, 1f))
        assertNull(WhiteBalanceMath.grayWorld(px(255, 255, 255)))
        assertNotNull(WhiteBalanceMath.whitePatch(px(200, 180, 150)))
    }
}

class MaskAndSpotTest {
    @Test
    fun darkSpotIsFoundInsideRadius() {
        val w = 64
        val h = 64
        val skin = (0xff shl 24) or (200 shl 16) or (160 shl 8) or 140
        val spot = (0xff shl 24) or (120 shl 16) or (70 shl 8) or 60
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if ((x - 32) * (x - 32) + (y - 32) * (y - 32) <= 9) spot else skin }
        val m = BlemishMath.detect(px, w, h, 32f, 32f, 10f)
        assertEquals(1, m[32 * w + 32].toInt())
        assertEquals(0, m[5 * w + 5].toInt())
        assertTrue(m.count { it.toInt() != 0 } < 100)
    }

    @Test
    fun flatSkinFallsBackToDisc() {
        val px = IntArray(32 * 32) { (0xff shl 24) or 0xc8a08c }
        val m = BlemishMath.detect(px, 32, 32, 16f, 16f, 8f)
        assertEquals(1, m[16 * 32 + 16].toInt())
    }

    @Test
    fun componentAndRegionGrow() {
        val w = 10
        val mask = FloatArray(100) { i -> if (i % w < 3 || i % w > 6) 1f else 0f }
        val left = MaskMath.component(mask, w, 10, 1, 1)
        assertEquals(30, left.count { it > 0f })
        assertEquals(0, MaskMath.component(mask, w, 10, 5, 5).count { it > 0f })
        val px = IntArray(100) { i -> if (i % w < 5) 0xff0000 else 0x00ff00 }
        val grown = MaskMath.regionGrow(px, w, 10, 0, 0, 30f, 1f)
        assertEquals(50, grown.count { it > 0f })
    }
}
