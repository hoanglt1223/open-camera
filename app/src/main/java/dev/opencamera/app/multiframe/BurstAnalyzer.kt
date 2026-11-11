/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureResult
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.CameraCaptureResults
import dev.opencamera.capture.SceneMeter
import dev.opencamera.pipeline.Burst
import kotlinx.coroutines.CompletableDeferred

/**
 * Full-resolution YUV analyzer. While idle it meters the scene every few frames; when a burst
 * is armed it copies frames into native memory until the burst is full.
 */
class BurstAnalyzer : ImageAnalysis.Analyzer {
    class Collected(val rotationDegrees: Int, val captureResult: CaptureResult?)

    private class Request(
        val burst: Burst,
        val count: Int,
        var skipRemaining: Int,
        val ready: (CaptureResult?) -> Boolean,
        val done: CompletableDeferred<Collected>,
    ) {
        var firstResult: CaptureResult? = null
        var rotation = 0
    }

    @Volatile
    var lastMeter: SceneMeter? = null
        private set

    @Volatile
    var frameSize: Size? = null
        private set

    @Volatile
    private var request: Request? = null
    private var frameCounter = 0

    /**
     * Arms a burst. Frames are skipped while [ready] returns false (new exposure not applied
     * yet), up to [maxSkip] frames.
     */
    fun arm(
        burst: Burst,
        count: Int,
        maxSkip: Int,
        ready: (CaptureResult?) -> Boolean,
    ): CompletableDeferred<Collected> {
        val done = CompletableDeferred<Collected>()
        request = Request(burst, count, maxSkip, ready, done)
        return done
    }

    /** Forgets stream state when use cases are rebound. */
    fun resetStream() {
        frameSize = null
        lastMeter = null
    }

    fun disarm() {
        request?.done?.cancel()
        request = null
    }

    override fun analyze(image: ImageProxy) {
        try {
            frameSize = Size(image.width, image.height)
            val req = request
            if (req == null) {
                if (frameCounter++ % 8 == 0) meter(image)
                return
            }
            val result = captureResult(image)
            if (req.burst.frameCount == 0 && req.skipRemaining > 0 && !req.ready(result)) {
                req.skipRemaining--
                return
            }
            val planes = image.planes
            val added = req.burst.addFrame(
                planes[0].buffer, planes[0].rowStride,
                planes[1].buffer, planes[2].buffer, planes[1].rowStride, planes[1].pixelStride,
            )
            if (req.burst.frameCount == 1) {
                req.firstResult = result
                req.rotation = image.imageInfo.rotationDegrees
            }
            if (!added || req.burst.frameCount >= req.count) {
                request = null
                if (req.burst.frameCount > 0) {
                    req.done.complete(Collected(req.rotation, req.firstResult))
                } else {
                    req.done.completeExceptionally(IllegalStateException("No frame captured"))
                }
            }
        } catch (e: Exception) {
            request?.done?.completeExceptionally(e)
            request = null
        } finally {
            image.close()
        }
    }

    private fun meter(image: ImageProxy) {
        // Some CameraX backends (camera-pipe) attach no CaptureResult to analysis frames; meter
        // from luma with a typical indoor exposure then, so bursts still run as HDR.
        val result = captureResult(image)
        if (result == null && !warnedNoResult) {
            warnedNoResult = true
            Log.w(LOG_TAG, "No CaptureResult on analysis frames, metering from luma only")
        }
        val exposure = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: FALLBACK_EXPOSURE_NS
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: FALLBACK_ISO
        val aperture = result?.get(CaptureResult.LENS_APERTURE) ?: 1.8f
        val plane = image.planes[0]
        val buf = plane.buffer
        val step = 16
        var sum = 0L
        var clipped = 0
        var n = 0
        var y = 0
        while (y < image.height) {
            val row = y * plane.rowStride
            var x = 0
            while (x < image.width) {
                val v = buf.get(row + x).toInt() and 0xff
                sum += v
                if (v >= 250) clipped++
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return
        lastMeter = SceneMeter(exposure, iso, sum.toFloat() / n, clipped.toFloat() / n, aperture)
    }

    @Volatile
    private var warnedNoResult = false

    companion object {
        private const val LOG_TAG = "BurstAnalyzer"
        private const val FALLBACK_EXPOSURE_NS = 20_000_000L
        private const val FALLBACK_ISO = 200

        @SuppressLint("RestrictedApi")
        fun captureResult(image: ImageProxy): CaptureResult? = runCatching {
            CameraCaptureResults.retrieveCameraCaptureResult(image.imageInfo)?.captureResult
        }.getOrNull()
    }
}
