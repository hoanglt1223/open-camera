/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import org.junit.Assert.assertEquals
import org.junit.Test

class RawExposureCapTest {
    private val cap = MultiFrameCaptureController.RAW_MAX_EXPOSURE_NS

    @Test
    fun shortExposureIsKept() {
        assertEquals(100_000_000L to 800, MultiFrameCaptureController.capRawExposure(100_000_000L, 800, 6400))
    }

    @Test
    fun longExposureTradesTimeForGain() {
        assertEquals(cap to 2000, MultiFrameCaptureController.capRawExposure(1_000_000_000L, 500, 6400))
    }

    @Test
    fun gainStopsAtTheSensorMaximum() {
        assertEquals(cap to 6400, MultiFrameCaptureController.capRawExposure(16_000_000_000L, 1600, 6400))
    }

    @Test
    fun autoExposurePlanIsUntouched() {
        assertEquals(null to null, MultiFrameCaptureController.capRawExposure(null, null, 6400))
    }
}
