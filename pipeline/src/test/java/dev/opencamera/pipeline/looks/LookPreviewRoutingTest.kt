/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookPreviewRoutingTest {
    @Test
    fun sdrPreviewGoesThroughTheRenderer() {
        assertTrue(LookPreviewSurfaceProvider.usesRenderer(enabled = true, sdr = true))
    }

    @Test
    fun failedRendererFallsBackToPlainPreview() {
        assertFalse(LookPreviewSurfaceProvider.usesRenderer(enabled = false, sdr = true))
    }

    @Test
    fun hdrPreviewSkipsTheEightBitRenderer() {
        assertFalse(LookPreviewSurfaceProvider.usesRenderer(enabled = true, sdr = false))
    }
}
