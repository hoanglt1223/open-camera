/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposurePlannerTest {
    private val caps = ExposureCaps(
        minExposureNs = 10_000L,
        maxExposureNs = 8_000_000_000L,
        minIso = 100,
        maxIso = 6400,
    )
    private val handheld = MotionState(angularRms = 0.015f, onTripod = false)
    private val shaky = MotionState(angularRms = 0.5f, onTripod = false)
    private val tripod = MotionState(angularRms = 0.002f, onTripod = true)

    private val daylight = SceneMeter(1_000_000L, 50, 118f, 0.02f)       // 1/1000 s ISO 50
    private val office = SceneMeter(10_000_000L, 200, 110f, 0.001f)     // 1/100 s ISO 200
    private val darkRoom = SceneMeter(66_000_000L, 3200, 40f, 0f)       // 1/15 s ISO 3200, dim

    @Test
    fun luxEstimateOrdersScenes() {
        assertTrue(daylight.luxEstimate > office.luxEstimate)
        assertTrue(office.luxEstimate > darkRoom.luxEstimate)
        assertTrue(darkRoom.luxEstimate < ExposurePlanner.NIGHT_LUX)
        assertTrue(office.luxEstimate in 50f..1000f)
    }

    @Test
    fun hdrUnderexposesMoreWhenHighlightsClip() {
        val plan = ExposurePlanner.plan(MultiFrameSetting.HDR_ON, daylight, handheld, caps)
        assertEquals(BurstKind.HDR, plan.kind)
        assertEquals(-1f, plan.evCompensation)
        assertEquals(1f, plan.toneEvStep)
        assertTrue(plan.frameCount in 4..15)
    }

    @Test
    fun hdrUsesFewerFramesWithMotion() {
        val steady = ExposurePlanner.plan(MultiFrameSetting.HDR_ON, office, handheld, caps)
        val moving = ExposurePlanner.plan(MultiFrameSetting.HDR_ON, office, shaky, caps)
        assertTrue(moving.frameCount < steady.frameCount)
    }

    @Test
    fun autoSwitchesToNightInTheDark() {
        val plan = ExposurePlanner.plan(MultiFrameSetting.HDR_AUTO, darkRoom, handheld, caps)
        assertEquals(BurstKind.NIGHT, plan.kind)
    }

    @Test
    fun autoKeepsHdrInDimRooms() {
        // Lamp-lit room: AE at 1/20 s ISO 1600 reaches near mid-grey (estimate ~7 lux).
        val dimRoom = SceneMeter(50_000_000L, 1600, 105f, 0f)
        assertEquals(BurstKind.HDR, ExposurePlanner.plan(MultiFrameSetting.HDR_AUTO, dimRoom, handheld, caps).kind)
        // Restaurant: 1/30 s ISO 2500, a bit under mid-grey.
        val restaurant = SceneMeter(33_000_000L, 2500, 90f, 0.001f)
        assertEquals(BurstKind.HDR, ExposurePlanner.plan(MultiFrameSetting.HDR_AUTO, restaurant, handheld, caps).kind)
        // AE at its limits but the frame still bright enough: not night.
        assertFalse(ExposurePlanner.wantsNight(SceneMeter(66_000_000L, 3200, 118f, 0f).copy(aperture = 2.8f)))
        // AE at its limits and the frame stays dark: night.
        assertTrue(ExposurePlanner.wantsNight(SceneMeter(66_000_000L, 3200, 60f, 0f).copy(aperture = 2.8f)))
    }

    @Test
    fun nightHandheldIsBlurLimitedAndTripodGoesLonger() {
        val hand = ExposurePlanner.plan(MultiFrameSetting.NIGHT, darkRoom, handheld, caps)
        val still = ExposurePlanner.plan(MultiFrameSetting.NIGHT, darkRoom, tripod, caps)
        val handT = hand.exposureTimeNs!!
        val stillT = still.exposureTimeNs!!
        assertTrue(handT <= 333_000_000L)
        assertTrue(stillT > handT)
        assertTrue(hand.frameCount in 6..15)
        assertTrue(hand.iso!! in caps.minIso..caps.maxIso)
        // Total brightness (t x ISO) should exceed the AE result in a dim scene.
        assertTrue(handT.toDouble() * hand.iso!! > darkRoom.exposureTimeNs.toDouble() * darkRoom.iso)
    }

    @Test
    fun astroNeedsTripod() {
        val night = SceneMeter(250_000_000L, 6400, 12f, 0f)
        assertEquals(BurstKind.NIGHT, ExposurePlanner.plan(MultiFrameSetting.ASTRO, night, handheld, caps).kind)
        val astro = ExposurePlanner.plan(MultiFrameSetting.ASTRO, night, tripod, caps)
        assertEquals(BurstKind.ASTRO, astro.kind)
        assertTrue(astro.exposureTimeNs!! >= 4_000_000_000L)
        assertTrue(astro.iso!! <= 1600)
        assertTrue(astro.frameCount in 4..16)
        assertTrue(ExposurePlanner.suggestAstro(night, tripod))
        assertFalse(ExposurePlanner.suggestAstro(night, handheld))
    }

    @Test
    fun astroRespectsHalExposureLimit() {
        val night = SceneMeter(250_000_000L, 6400, 12f, 0f)
        val shortCaps = caps.copy(maxExposureNs = 1_000_000_000L)
        val astro = ExposurePlanner.plan(MultiFrameSetting.ASTRO, night, tripod, shortCaps)
        assertTrue(astro.exposureTimeNs!! <= 1_000_000_000L)
        assertEquals(16, astro.frameCount)
    }

    @Test
    fun zoomedHdrUsesSuperRes() {
        val plan = ExposurePlanner.plan(MultiFrameSetting.HDR_ON, office, handheld, caps, zoomRatio = 2f)
        assertEquals(BurstKind.SUPER_RES, plan.kind)
    }

    @Test
    fun offIsSingleFrame() {
        val plan = ExposurePlanner.plan(MultiFrameSetting.OFF, darkRoom, handheld, caps)
        assertEquals(BurstKind.SINGLE, plan.kind)
        assertEquals(1, plan.frameCount)
    }
}
