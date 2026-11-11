/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.robustness

import dev.opencamera.app.robustness.FeatureGuard.BindAttempt
import dev.opencamera.app.robustness.FeatureGuard.Dropped
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureGuardTest {
    private val capture = 1
    private val analysis = 2
    private val video = 4

    @Test
    fun photoWithAnalysisDropsAnalysisThenEffectThenBaseline() {
        assertEquals(
            listOf(
                BindAttempt(capture or analysis, false, Dropped.NONE),
                BindAttempt(capture, false, Dropped.MULTI_FRAME),
                BindAttempt(capture, true, Dropped.LOOK_PREVIEW),
            ),
            FeatureGuard.bindChain(capture or analysis, capture, analysis, capture),
        )
    }

    @Test
    fun plainPhotoOnlyDropsEffect() {
        assertEquals(
            listOf(
                BindAttempt(capture, false, Dropped.NONE),
                BindAttempt(capture, true, Dropped.LOOK_PREVIEW),
            ),
            FeatureGuard.bindChain(capture, capture, analysis, capture),
        )
    }

    @Test
    fun qrKeepsAnalysis() {
        val chain = FeatureGuard.bindChain(analysis, analysis, analysis, capture)
        assertEquals(listOf(analysis, analysis), chain.map { it.useCases })
        assertEquals(listOf(false, true), chain.map { it.dropEffect })
    }

    @Test
    fun videoNeverGainsAnalysis() {
        val chain = FeatureGuard.bindChain(video, video, analysis, capture)
        assertEquals(true, chain.all { it.useCases == video })
        assertEquals(Dropped.NONE, chain.first().dropped)
    }
}
