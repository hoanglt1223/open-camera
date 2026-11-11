/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import dev.opencamera.capture.BurstKind
import dev.opencamera.capture.BurstPlan
import kotlin.coroutines.cancellation.CancellationException

/** Pro manual stills and the capture fallback chain of the full-resolution session. */
object ManualStill {
    /**
     * From this exposure on, a CameraX RAW still queues behind preview frames as long as itself
     * (about 3 frame times before the shot starts), so a one-frame Camera2 burst is faster.
     */
    const val MIN_CAMERA2_EXPOSURE_NS = 250_000_000L

    /** One-frame manual plan for a Pro exposure long enough to run as a Camera2 burst, else null. */
    fun plan(exposureNs: Long?, iso: Int?): BurstPlan? {
        if (exposureNs == null || iso == null || iso <= 0 || exposureNs < MIN_CAMERA2_EXPOSURE_NS) return null
        return BurstPlan(BurstKind.SINGLE, 1, exposureTimeNs = exposureNs, iso = iso)
    }

    /**
     * Runs [primary]; if it fails, runs [fallback] when there is one. Returns null when nothing
     * worked. Cancellation is never treated as a failure and always propagates.
     */
    suspend fun <T : Any> withFallback(
        primary: suspend () -> T,
        fallback: (suspend () -> T)?,
        onFailure: (step: String, error: Throwable) -> Unit,
    ): T? {
        try {
            return primary()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailure("primary", e)
        }
        fallback ?: return null
        return try {
            fallback()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onFailure("fallback", e)
            null
        }
    }
}
