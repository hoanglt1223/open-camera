/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.opencamera.app.multiframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullResStreamTest {
    @Test
    fun mainSensorNeedsSixMegapixels() {
        val main = 4624L * 3472
        assertTrue(MultiFrameCaptureController.isFullResStream(4624, 3472, main))
        assertFalse(MultiFrameCaptureController.isFullResStream(1920, 1440, main))
    }

    @Test
    fun smallSensorAtItsLargestSizeIsFullRes() {
        assertTrue(MultiFrameCaptureController.isFullResStream(1600, 1200, 1600L * 1200))
        assertFalse(MultiFrameCaptureController.isFullResStream(1280, 960, 1600L * 1200))
    }

    @Test
    fun unknownLargestSizeFallsBackToSixMegapixels() {
        assertFalse(MultiFrameCaptureController.isFullResStream(1600, 1200, null))
        assertFalse(MultiFrameCaptureController.isFullResStream(1600, 1200, 0))
    }
}
