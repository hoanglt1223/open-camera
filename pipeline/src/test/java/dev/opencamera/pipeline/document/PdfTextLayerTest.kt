/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.document

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfTextLayerTest {
    @Test
    fun wordsScaleIntoThePlacedImage() {
        val box = PdfExporter.placeWord(PdfExporter.Word("Hello", 100, 200, 300, 240), 0.5f, 18, 30)
        assertArrayEquals(floatArrayOf(68f, 130f, 168f, 150f), box, 1e-4f)
    }

    @Test
    fun emptyOrDegenerateWordsAreSkipped() {
        assertNull(PdfExporter.placeWord(PdfExporter.Word(" ", 0, 0, 10, 10), 1f, 0, 0))
        assertNull(PdfExporter.placeWord(PdfExporter.Word("x", 10, 0, 10, 10), 1f, 0, 0))
        assertNull(PdfExporter.placeWord(PdfExporter.Word("x", 0, 10, 10, 5), 1f, 0, 0))
    }
}
