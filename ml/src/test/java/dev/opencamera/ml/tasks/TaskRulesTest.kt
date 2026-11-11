/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRulesTest {
    @Test
    fun nightAndPortraitWorkWithoutClassifier() {
        assertEquals(Scene.NIGHT, SceneRules.decide(emptyList(), FrameStats(20f, 0f, 0f)).first)
        assertEquals(Scene.PORTRAIT, SceneRules.decide(emptyList(), FrameStats(120f, 0f, 0.1f)).first)
        assertEquals(Scene.SKY, SceneRules.decide(emptyList(), FrameStats(150f, 0.6f, 0f)).first)
        assertEquals(Scene.NONE, SceneRules.decide(emptyList(), FrameStats(120f, 0f, 0f)).first)
    }

    @Test
    fun labelsMapToPresets() {
        val stats = FrameStats(120f, 0f, 0f)
        assertEquals(Scene.FOOD, SceneRules.decide(listOf("pizza" to 0.6f, "plate" to 0.2f), stats).first)
        assertEquals(Scene.TEXT, SceneRules.decide(listOf("menu" to 0.5f), stats).first)
        assertEquals(Scene.DOCUMENT, SceneRules.decide(listOf("envelope" to 0.4f), stats).first)
        assertEquals(Scene.PLANT, SceneRules.decide(listOf("daisy" to 0.9f), stats).first)
        // Weak evidence stays on Auto.
        assertEquals(Scene.NONE, SceneRules.decide(listOf("pizza" to 0.05f), stats).first)
    }

    @Test
    fun skyStatsCountBlueTopThird() {
        val w = 30
        val h = 30
        val px = IntArray(w * h) { i -> if (i / w < 10) 0xff5080e0.toInt() else 0xff303030.toInt() }
        val s = SceneRules.stats(px, w, h, 0f)
        assertEquals(1f, s.skyFraction, 1e-6f)
    }

    @Test
    fun nimaMeanIsExpectedScore() {
        val p = FloatArray(10).also { it[4] = 1f }
        assertEquals(5f, AestheticScorer.nimaMean(p), 1e-6f)
        assertEquals(0f, AestheticScorer.nimaMean(FloatArray(10)), 0f)
    }

    @Test
    fun sharpnessPrefersDetail() {
        val w = 32
        val flat = IntArray(w * w) { 0xff808080.toInt() }
        val checker = IntArray(w * w) { i -> if ((i % w + i / w) % 2 == 0) 0xffffffff.toInt() else 0xff000000.toInt() }
        assertEquals(0f, Classical.sharpness(flat, w, w), 1e-6f)
        assertTrue(Classical.sharpness(checker, w, w) > 0.9f)
        assertTrue(Classical.quality(checker, w, w) > Classical.quality(flat, w, w))
    }

    @Test
    fun compositionPointsToNearestThird() {
        val w = 60
        val h = 40
        val m = Mask(w, h)
        // Subject around (0.45, 0.45).
        for (y in 15 until 21) for (x in 24 until 30) m.data[y * w + x] = 1f
        val s = Composition.suggest(m)
        assertNotNull(s)
        s!!
        assertEquals(1f / 3, s.targetX, 1e-6f)
        assertEquals(1f / 3, s.targetY, 1e-6f)
        val c = s.crop!!
        // Subject lands on the crop's thirds point.
        assertEquals(1f / 3, (s.subjectX - c.left) / c.width, 0.03f)
        assertEquals(1f / 3, (s.subjectY - c.top) / c.height, 0.03f)
    }

    @Test
    fun compositionNeedsASubject() {
        assertNull(Composition.suggest(Mask(10, 10)))
        assertNull(Composition.suggest(Mask(10, 10, FloatArray(100) { 1f })))
    }

    @Test
    fun classicalSaliencyHighlightsOddPatch() {
        val w = 64
        val h = 48
        val px = IntArray(w * h) { 0xff406040.toInt() }
        for (y in 20 until 28) for (x in 40 until 48) px[y * w + x] = 0xffff2020.toInt()
        val m = Classical.saliency(px, w, h)
        val s = Composition.suggest(m)!!
        assertEquals(44f / w, s.subjectX, 0.08f)
        assertEquals(24f / h, s.subjectY, 0.08f)
    }

    @Test
    fun maskSamplingAndInversion() {
        val m = Mask(2, 1, floatArrayOf(0f, 1f))
        assertEquals(0f, m.sample(0f, 0.5f), 1e-6f)
        assertEquals(1f, m.sample(1f, 0.5f), 1e-6f)
        assertEquals(0.5f, m.sample(0.5f, 0.5f), 1e-6f)
        assertEquals(1f, m.inverted().data[0], 0f)
    }
}
