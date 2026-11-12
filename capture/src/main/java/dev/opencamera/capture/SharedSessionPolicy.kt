/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

/**
 * Whether Photo and Video can share one session (Preview + ImageCapture + VideoCapture bound
 * once), so switching between them only changes request options instead of reopening the camera.
 *
 * Only a plain JPEG photo session qualifies: full-resolution YUV/RAW streams, the analysis stream,
 * extensions and ZSL (CameraX turns ZSL off next to VideoCapture) keep the per-mode bind. The
 * preview box must be the same in both modes, because the controller's viewport also crops the
 * recorded video.
 */
object SharedSessionPolicy {
    data class Inputs(
        val photoStream: StreamConfig,
        val photoJpeg: Boolean,
        val noExtension: Boolean,
        val analysisStream: Boolean,
        val zsl: Boolean,
        val samePreviewBox: Boolean,
        val videoSdr: Boolean,
        /** Video frame rate above 30 fps (needs a high-speed or 60 fps stream combination). */
        val videoHighFrameRate: Boolean,
        val singleCaptureIntent: Boolean,
        /** A shared bind failed, or lowered the photo size, on this camera earlier. */
        val knownUnsupported: Boolean,
    )

    fun eligible(i: Inputs): Boolean =
        i.photoStream == StreamConfig.STANDARD && i.photoJpeg && i.noExtension && !i.analysisStream &&
            !i.zsl && i.samePreviewBox && i.videoSdr && !i.videoHighFrameRate &&
            !i.singleCaptureIntent && !i.knownUnsupported

    /**
     * The shared bind is kept only when the still size is at least [MIN_PHOTO_SHARE] of what the
     * photo-only session would pick. Unknown sizes count as degraded, so the per-mode bind wins.
     */
    fun photoDegraded(boundPixels: Long?, expectedPixels: Long?): Boolean {
        if (boundPixels == null || expectedPixels == null || expectedPixels <= 0) return true
        return boundPixels < expectedPixels * MIN_PHOTO_SHARE
    }

    const val MIN_PHOTO_SHARE = 0.9
}
