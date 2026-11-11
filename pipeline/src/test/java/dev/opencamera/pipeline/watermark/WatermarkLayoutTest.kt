/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.watermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkLayoutTest {
    @Test
    fun noneHasNoLayout() {
        assertNull(WatermarkLayout.compute(WatermarkTemplate.NONE, 4000, 3000))
    }

    @Test
    fun filmBorderScalesWithShortSide() {
        val l = WatermarkLayout.compute(WatermarkTemplate.FILM_BORDER, 4000, 3000)!!
        // 3.5% sides/top and 13% bottom of the 3000 px short side.
        assertEquals(Box(105, 105, 4105, 3105), l.image)
        assertEquals(4000 + 210, l.canvasWidth)
        assertEquals(3000 + 105 + 390, l.canvasHeight)
        val portrait = WatermarkLayout.compute(WatermarkTemplate.FILM_BORDER, 3000, 4000)!!
        assertEquals(l.image.left, portrait.image.left)
        assertEquals(l.canvasHeight - 3000, portrait.canvasHeight - 4000)
        // Same relative layout at a different resolution.
        val small = WatermarkLayout.compute(WatermarkTemplate.FILM_BORDER, 400, 300)!!
        assertEquals(l.model.size / 10f, small.model.size, 0.01f)
    }

    @Test
    fun exifStripAddsBandBelowImage() {
        val l = WatermarkLayout.compute(WatermarkTemplate.EXIF_STRIP, 4624, 3472)!!
        assertEquals(Box(0, 0, 4624, 3472), l.image)
        assertEquals(4624, l.canvasWidth)
        assertEquals(3472 + 347, l.canvasHeight)
        for (slot in listOf(l.model, l.exposure, l.date)) {
            assertTrue(slot.baseline in 3472..l.canvasHeight)
            assertTrue(slot.maxWidth in 1 until l.canvasWidth / 2)
        }
        val d = l.divider!!
        assertTrue(d.top > 3472 && d.bottom < l.canvasHeight && d.width >= 1)
    }

    @Test
    fun textSlotsFitInsideCanvas() {
        for (t in listOf(WatermarkTemplate.FILM_BORDER, WatermarkTemplate.EXIF_STRIP)) {
            for ((w, h) in listOf(640 to 480, 480 to 640, 9248 to 6944, 1000 to 1000)) {
                val l = WatermarkLayout.compute(t, w, h)!!
                for (slot in listOf(l.model, l.exposure, l.date)) {
                    val left = if (slot.align == Align.START) slot.x else slot.x - slot.maxWidth
                    assertTrue("$t $w x $h", left >= 0 && left + slot.maxWidth <= l.canvasWidth)
                    assertTrue("$t $w x $h", slot.baseline - slot.size >= l.image.bottom)
                    assertTrue("$t $w x $h", slot.baseline <= l.canvasHeight)
                }
            }
        }
    }

    @Test
    fun exposureLineOmitsMissingFields() {
        val all = ExifFields("M", 5.4f, 1.89f, 1 / 120.0, 200, "2026-11-11 09:30")
        assertEquals("5.4mm  f/1.9  1/120s  ISO 200", all.exposureLine())
        assertEquals("f/1.9  ISO 200", all.copy(focalLengthMm = null, exposureSeconds = null).exposureLine())
        assertEquals("", ExifFields().exposureLine())
        assertEquals("27mm", ExifFields(focalLengthMm = 27f, iso = 0).exposureLine())
    }

    @Test
    fun shutterFormatting() {
        assertEquals("1/4000s", ExifFields.formatShutter(0.00025))
        assertEquals("1/3s", ExifFields.formatShutter(0.333 / 1.0 - 0.04))
        assertEquals("2s", ExifFields.formatShutter(2.0))
        assertEquals("0.5s", ExifFields.formatShutter(0.5))
    }
}
