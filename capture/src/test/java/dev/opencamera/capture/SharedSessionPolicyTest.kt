/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSessionPolicyTest {
    private val ok = SharedSessionPolicy.Inputs(
        photoStream = StreamConfig.STANDARD, photoJpeg = true, noExtension = true, analysisStream = false,
        zsl = false, samePreviewBox = true, videoSdr = true, videoHighFrameRate = false,
        singleCaptureIntent = false, knownUnsupported = false,
    )

    @Test
    fun plainJpegSessionShares() = assertTrue(SharedSessionPolicy.eligible(ok))

    @Test
    fun anyBlockerKeepsPerModeBind() {
        listOf(
            ok.copy(photoStream = StreamConfig.FULL_RES_YUV),
            ok.copy(photoStream = StreamConfig.FULL_RES_RAW),
            ok.copy(photoJpeg = false),
            ok.copy(noExtension = false),
            ok.copy(analysisStream = true),
            ok.copy(zsl = true),
            ok.copy(samePreviewBox = false),
            ok.copy(videoSdr = false),
            ok.copy(videoHighFrameRate = true),
            ok.copy(singleCaptureIntent = true),
            ok.copy(knownUnsupported = true),
        ).forEach { assertFalse(it.toString(), SharedSessionPolicy.eligible(it)) }
    }

    @Test
    fun photoSizeCheck() {
        assertFalse(SharedSessionPolicy.photoDegraded(16_000_000, 16_000_000))
        assertFalse(SharedSessionPolicy.photoDegraded(14_500_000, 16_000_000))
        assertTrue(SharedSessionPolicy.photoDegraded(8_000_000, 16_000_000))
        assertTrue(SharedSessionPolicy.photoDegraded(null, 16_000_000))
        assertTrue(SharedSessionPolicy.photoDegraded(16_000_000, null))
        assertTrue(SharedSessionPolicy.photoDegraded(16_000_000, 0))
    }
}
