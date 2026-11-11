/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import kotlin.math.abs

/**
 * Session capture callback keeping the last few capture results, matched to frames by sensor
 * timestamp (ImageProxy timestamps are the SENSOR_TIMESTAMP of their request).
 */
class CaptureResultTracker : CameraCaptureSession.CaptureCallback() {
    private val ring = arrayOfNulls<TotalCaptureResult>(SIZE)
    private var next = 0

    @Volatile
    var latest: TotalCaptureResult? = null
        private set

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult,
    ) {
        synchronized(ring) {
            ring[next] = result
            next = (next + 1) % SIZE
        }
        latest = result
    }

    /** Result whose sensor timestamp is within 1 ms of [timestampNs], or null. */
    fun find(timestampNs: Long): CaptureResult? = synchronized(ring) {
        ring.filterNotNull().minByOrNull { abs((it.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L) - timestampNs) }
            ?.takeIf { abs((it.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L) - timestampNs) <= MATCH_NS }
    }

    fun clear() {
        synchronized(ring) { ring.fill(null) }
        latest = null
    }

    private companion object {
        const val SIZE = 24
        const val MATCH_NS = 1_000_000L
    }
}
