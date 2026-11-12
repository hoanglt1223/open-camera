/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

/**
 * Shutter readiness for single-frame captures that save in the background. The shutter is ready
 * again once the sensor part of a shot is done; encoding and writing continue behind it, bounded
 * by [maxPendingSaves] so a burst of taps cannot pile up full-size images in memory.
 *
 * Not thread safe: drive it from one thread (the main thread in the app).
 */
class CaptureReadiness(private val maxPendingSaves: Int = DEFAULT_MAX_PENDING_SAVES) {
    init {
        require(maxPendingSaves >= 1)
    }

    /** A shot was started and its exposure is not known to be done yet. */
    var exposing = false
        private set

    /** Shots started and not yet saved (or failed), the one exposing included. */
    var pendingSaves = 0
        private set

    val ready get() = !exposing && pendingSaves < maxPendingSaves
    val saving get() = pendingSaves > 0

    /** Starts a shot when [ready]; returns false (and changes nothing) otherwise. */
    fun tryStart(): Boolean {
        if (!ready) return false
        exposing = true
        pendingSaves++
        return true
    }

    /** The sensor part of the current shot is over; the save goes on in the background. */
    fun exposureDone() {
        exposing = false
    }

    /** A shot finished, saved or failed. Extra calls are ignored. */
    fun finished() {
        if (pendingSaves == 0) return
        pendingSaves--
        // The last shot cannot still be exposing once nothing is left in flight.
        if (pendingSaves == 0) exposing = false
    }

    /** Drops everything in flight, e.g. when the save callbacks can no longer arrive. */
    fun reset() {
        exposing = false
        pendingSaves = 0
    }

    companion object {
        const val DEFAULT_MAX_PENDING_SAVES = 2
    }
}
