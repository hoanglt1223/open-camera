/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import dev.opencamera.capture.BurstSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageQualityTest {
    private val gauguin = ImageQuality.Megapixels(raw = 16, yuv = 8)

    @Test
    fun autoUsesRawOnlyWhenClearlyLarger() {
        assertEquals(16, gauguin.auto)
        assertEquals(12, ImageQuality.Megapixels(raw = 12, yuv = 12).auto)
        assertEquals(8, ImageQuality.Megapixels(raw = null, yuv = 8).auto)
    }

    @Test
    fun maximumIsOfferedOnlyWithRaw() {
        assertEquals(listOf(BurstSource.AUTO, BurstSource.RAW, BurstSource.YUV), ImageQuality.choices(gauguin))
        assertEquals(listOf(BurstSource.AUTO, BurstSource.YUV), ImageQuality.choices(ImageQuality.Megapixels(null, 8)))
        assertEquals(listOf(BurstSource.AUTO, BurstSource.YUV), ImageQuality.choices(null))
    }

    @Test
    fun quickToggleCyclesAndRecoversFromAMissingChoice() {
        assertEquals(BurstSource.RAW, ImageQuality.next(BurstSource.AUTO, gauguin))
        assertEquals(BurstSource.YUV, ImageQuality.next(BurstSource.RAW, gauguin))
        assertEquals(BurstSource.AUTO, ImageQuality.next(BurstSource.YUV, gauguin))
        // A RAW choice saved on a camera without RAW moves on to a valid one.
        assertEquals(BurstSource.YUV, ImageQuality.next(BurstSource.RAW, null))
    }
}
