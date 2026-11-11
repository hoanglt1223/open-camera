/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPolicyTest {
    private val base = StreamPolicy.Inputs(
        setting = MultiFrameSetting.HDR_AUTO, creativeActive = false, jpegOutput = true, noExtension = true,
        backCamera = true, singleCaptureIntent = false, flashOff = true,
        rawCapable = true, source = BurstSource.AUTO,
    )

    @Test
    fun hdrUsesFullResYuv() = assertEquals(StreamConfig.FULL_RES_YUV, StreamPolicy.choose(base))

    @Test
    fun nightAndAstroPreferRawWhenAvailable() {
        assertEquals(StreamConfig.FULL_RES_RAW, StreamPolicy.choose(base.copy(setting = MultiFrameSetting.NIGHT)))
        assertEquals(StreamConfig.FULL_RES_RAW, StreamPolicy.choose(base.copy(setting = MultiFrameSetting.ASTRO)))
        assertEquals(
            StreamConfig.FULL_RES_YUV,
            StreamPolicy.choose(base.copy(setting = MultiFrameSetting.NIGHT, rawCapable = false)),
        )
        assertEquals(
            StreamConfig.FULL_RES_YUV,
            StreamPolicy.choose(base.copy(setting = MultiFrameSetting.NIGHT, source = BurstSource.YUV)),
        )
        assertEquals(StreamConfig.FULL_RES_RAW, StreamPolicy.choose(base.copy(source = BurstSource.RAW)))
    }

    @Test
    fun creativeModesUseYuv() = assertEquals(
        StreamConfig.FULL_RES_YUV,
        StreamPolicy.choose(base.copy(setting = MultiFrameSetting.OFF, creativeActive = true, source = BurstSource.RAW)),
    )

    @Test
    fun anythingTheAppPathCannotDoStaysStandard() {
        listOf(
            base.copy(setting = MultiFrameSetting.OFF),
            base.copy(jpegOutput = false),
            base.copy(noExtension = false),
            base.copy(backCamera = false),
            base.copy(singleCaptureIntent = true),
            base.copy(flashOff = false),
            base.copy(flashOff = false, creativeActive = true),
        ).forEach { assertEquals(it.toString(), StreamConfig.STANDARD, StreamPolicy.choose(it)) }
    }

    @Test
    fun fallbackChainEndsAtStandard() {
        assertEquals(StreamConfig.FULL_RES_YUV, StreamPolicy.fallback(StreamConfig.FULL_RES_RAW))
        assertEquals(StreamConfig.STANDARD, StreamPolicy.fallback(StreamConfig.FULL_RES_YUV))
        assertNull(StreamPolicy.fallback(StreamConfig.STANDARD))
    }

    @Test
    fun nightAutoNeedsMeasuredExposure() {
        // 20 ms / ISO 200 assumed with a mid-dark frame: lux estimate is low but only a guess.
        val guessed = SceneMeter(20_000_000L, 200, 60f, 0f, measured = false)
        assertFalse(ExposurePlanner.wantsNight(guessed))
        assertTrue(ExposurePlanner.wantsNight(guessed.copy(meanLuma = 20f)))
        // Real result: AE at 1/15 s and ISO 3200 is a night scene whatever the luma.
        assertTrue(ExposurePlanner.wantsNight(SceneMeter(66_000_000L, 3200, 110f, 0f, measured = true)))
        assertFalse(ExposurePlanner.wantsNight(SceneMeter(10_000_000L, 100, 110f, 0f, measured = true)))
        assertFalse(
            ExposurePlanner.suggestAstro(guessed.copy(meanLuma = 5f), MotionState(0f, onTripod = true))
        )
    }
}
