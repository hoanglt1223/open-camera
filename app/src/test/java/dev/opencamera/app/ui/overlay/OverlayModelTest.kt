/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import dev.opencamera.app.models.CameraMode
import dev.opencamera.app.pro.MeteringMode
import dev.opencamera.app.pro.ProCapabilities
import dev.opencamera.app.pro.ProRanges
import dev.opencamera.app.pro.ProSettings
import dev.opencamera.app.pro.WbMode
import dev.opencamera.capture.MultiFrameSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayModelTest {
    @Test
    fun photoFeaturesMapToCarouselModes() {
        assertEquals(UiMode.PRO, UiModes.derive(CameraMode.PHOTO, true, true, MultiFrameSetting.NIGHT))
        assertEquals(UiMode.PORTRAIT, UiModes.derive(CameraMode.PHOTO, false, true, MultiFrameSetting.NIGHT))
        assertEquals(UiMode.NIGHT, UiModes.derive(CameraMode.PHOTO, false, false, MultiFrameSetting.NIGHT))
        assertEquals(UiMode.PHOTO, UiModes.derive(CameraMode.PHOTO, false, false, MultiFrameSetting.ASTRO))
        assertEquals(UiMode.VIDEO, UiModes.derive(CameraMode.VIDEO, true, true, MultiFrameSetting.NIGHT))
        assertEquals(UiMode.QR, UiModes.derive(CameraMode.QR, false, false, MultiFrameSetting.OFF))
    }

    @Test
    fun swipeSkipsMoreAndStopsAtEnds() {
        val modes = UiModes.available(proAvailable = true, videoAvailable = true, moreAvailable = true)
        assertEquals(UiMode.VIDEO, UiModes.swipe(UiMode.PHOTO, true, modes))
        assertEquals(UiMode.PORTRAIT, UiModes.swipe(UiMode.PHOTO, false, modes))
        assertNull(UiModes.swipe(UiMode.NIGHT, false, modes))
        assertNull(UiModes.swipe(UiMode.QR, true, modes))
    }

    @Test
    fun unavailableModesAreHidden() {
        val modes = UiModes.available(proAvailable = false, videoAvailable = false, moreAvailable = false)
        assertFalse(UiMode.PRO in modes)
        assertFalse(UiMode.VIDEO in modes)
        assertFalse(UiMode.MORE in modes)
        assertEquals(UiMode.QR, UiModes.swipe(UiMode.PHOTO, true, modes))
    }

    private val caps = ProCapabilities(
        cameraId = "0", manualSensor = true, manualPostProcessing = true, raw = true,
        ranges = ProRanges(iso = 50..6400, exposureNs = 100_000L..1_000_000_000L, minFocusDiopters = 10f, ev = -12..12),
        evStep = 1f / 6, fNumber = 1.8f, maxAeRegions = 1, activeArray = null, awbModes = emptySet(),
        cctMode = false, calibration = null,
    )
    private val labels = DialModel.Labels("Auto", "Inf", List(5) { "wb$it" }, listOf("C", "S", "M"))

    @Test
    fun isoDialSetsManualExposureAndBackToAuto() {
        val iso = DialModel(ProDial.ISO, caps, labels)
        val manual = iso.apply(ProSettings(), 3)
        assertTrue(manual.manualExposure)
        assertEquals(3, iso.index(manual))
        assertEquals(DialModel.DEFAULT_EXPOSURE_NS, manual.exposureNs)
        val auto = iso.apply(manual, 0)
        assertFalse(auto.manualExposure)
        assertEquals("Auto", iso.label(0))
    }

    @Test
    fun wbDialCoversPresetsAndKelvin() {
        val wb = DialModel(ProDial.WB, caps, labels)
        assertEquals(WbMode.DAYLIGHT, wb.apply(ProSettings(), 3).wbMode)
        val k = wb.apply(ProSettings(), 1 + DialModel.WB_PRESETS.size + 35)
        assertEquals(WbMode.KELVIN, k.wbMode)
        assertEquals(5500, k.kelvin)
        assertEquals("5500K", wb.summary(k))
        assertEquals(wb.count - 1, wb.index(k.copy(kelvin = ProRanges.KELVIN_MAX)))
    }

    @Test
    fun focusAndMeterDialsRoundTrip() {
        val focus = DialModel(ProDial.FOCUS, caps, labels)
        assertEquals("Inf", focus.label(1))
        val near = focus.apply(ProSettings(), focus.count - 1)
        assertEquals(10f, near.focusDiopters!!, 1e-4f)
        assertEquals(focus.count - 1, focus.index(near))
        val meter = DialModel(ProDial.METER, caps, labels)
        assertEquals(MeteringMode.MATRIX, meter.apply(ProSettings(), 2).metering)
    }

    @Test
    fun evDialIsCenteredOnZero() {
        val ev = DialModel(ProDial.EV, caps, labels)
        assertEquals(12, ev.index(ProSettings()))
        assertEquals(6, ev.apply(ProSettings(), 18).evIndex)
    }
}
