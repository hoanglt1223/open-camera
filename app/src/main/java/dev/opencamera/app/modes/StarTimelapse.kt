/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

import android.util.Log
import dev.opencamera.pipeline.Burst
import dev.opencamera.pipeline.MultiFrameMode
import dev.opencamera.pipeline.MultiFrameProcessor

/**
 * Receives finished timelapse frames. Implemented by the timelapse/hyperlapse encoder (video
 * modes); this side only produces night-merged frames.
 */
interface TimelapseFrameSink {
    /** One output frame, NV21, in stream orientation; [rotationDegrees] as for the stream. */
    fun onFrame(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int, captureTimeMs: Long)

    /** No more frames; [frames] were delivered. */
    fun onFinished(frames: Int)
}

/**
 * Star timelapse: each timelapse frame is a short burst merged with the Astro (or Night)
 * pipeline, so every frame has multi-frame noise reduction and hot-pixel suppression. When a
 * merge fails the first raw frame of the burst is used, so the sequence never has holes.
 */
class StarTimelapseAdapter(
    private val sink: TimelapseFrameSink,
    private val mode: MultiFrameMode = MultiFrameMode.ASTRO,
) {
    var delivered = 0
        private set

    /** Merges [burst] into one timelapse frame and hands it to the sink. Closes [burst]. */
    fun onBurst(burst: Burst, rotationDegrees: Int, captureTimeMs: Long) {
        burst.use {
            val image = MultiFrameProcessor.processOrFallback(it, mode, toneEvStep = 0f) ?: run {
                Log.w(TAG, "Empty burst at $captureTimeMs, frame skipped")
                return
            }
            sink.onFrame(image.nv21, image.width, image.height, rotationDegrees, captureTimeMs)
            delivered++
        }
    }

    fun finish() = sink.onFinished(delivered)

    private companion object {
        const val TAG = "StarTimelapse"
    }
}
