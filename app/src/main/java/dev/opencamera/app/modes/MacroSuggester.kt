/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

/**
 * Decides when Photo on the main camera should offer Macro: the lens sits at its closest focus
 * without finding focus for a while (subject nearer than [minFocusDiopters]), or focusing failed
 * repeatedly. Only reads results; it never moves focus or rebinds. Hysteresis keeps the chip
 * from flickering. Not thread-safe; feed it from one thread.
 */
class MacroSuggester(private val minFocusDiopters: Float) {
    private var nearFrames = 0
    private var clearFrames = 0
    private val failures = ArrayDeque<Long>()

    var suggested = false
        private set

    /**
     * One preview capture result: CONTROL_AF_STATE and LENS_FOCUS_DISTANCE (diopters), either
     * may be missing. Returns [suggested].
     */
    fun onFrame(afState: Int?, focusDistance: Float?): Boolean {
        if (minFocusDiopters <= 0f || afState == null || focusDistance == null) return suggested
        val atClosest = focusDistance >= minFocusDiopters * NEAR_FRACTION
        val focused = afState == AF_PASSIVE_FOCUSED || afState == AF_FOCUSED_LOCKED
        if (atClosest && !focused) {
            nearFrames++
            clearFrames = 0
            if (nearFrames >= SHOW_FRAMES) suggested = true
        } else {
            clearFrames++
            nearFrames = 0
            if (clearFrames >= HIDE_FRAMES) suggested = false
        }
        return suggested
    }

    /** A tap-to-focus that did not lock, at [nowMs]. Returns [suggested]. */
    fun onFocusFailed(nowMs: Long): Boolean {
        failures.addLast(nowMs)
        while (failures.isNotEmpty() && nowMs - failures.first() > FAILURE_WINDOW_MS) failures.removeFirst()
        if (failures.size >= FAILURES_TO_SUGGEST) {
            suggested = true
            clearFrames = 0
        }
        return suggested
    }

    fun reset() {
        nearFrames = 0
        clearFrames = 0
        failures.clear()
        suggested = false
    }

    companion object {
        const val NEAR_FRACTION = 0.9f
        const val SHOW_FRAMES = 15
        const val HIDE_FRAMES = 30
        const val FAILURES_TO_SUGGEST = 3
        const val FAILURE_WINDOW_MS = 8_000L

        // CaptureResult.CONTROL_AF_STATE values, inlined to keep this class free of Android.
        const val AF_PASSIVE_SCAN = 1
        const val AF_PASSIVE_FOCUSED = 2
        const val AF_FOCUSED_LOCKED = 4
        const val AF_NOT_FOCUSED_LOCKED = 5
        const val AF_PASSIVE_UNFOCUSED = 6
    }
}
