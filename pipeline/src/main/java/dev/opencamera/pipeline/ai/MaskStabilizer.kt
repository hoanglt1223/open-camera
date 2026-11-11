/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import dev.opencamera.ml.tasks.Mask

/**
 * Steadies live portrait masks: an exponential moving average over frames, and hysteresis on the
 * subject coverage so the blur only appears once a person is reliably seen and does not blink on
 * a single missed detection. While no subject is confirmed, [update] returns null (no blur).
 */
class MaskStabilizer(
    private val alpha: Float = 0.5f,
    private val onCoverage: Float = 0.04f,
    private val offCoverage: Float = 0.02f,
    private val framesToShow: Int = 2,
    private val framesToHide: Int = 3,
) {
    private var smoothed: Mask? = null
    private var hits = 0
    private var misses = 0

    var visible = false
        private set

    fun reset() {
        smoothed = null
        hits = 0
        misses = 0
        visible = false
    }

    /** [mask] null means no subject in this frame. Returns the mask to render, or null. */
    fun update(mask: Mask?): Mask? {
        val coverage = mask?.coverage() ?: 0f
        val prev = smoothed
        if (mask != null && (prev == null || prev.width != mask.width || prev.height != mask.height)) {
            // New geometry (lens, aspect or rotation change): start over from this frame.
            smoothed = Mask(mask.width, mask.height, mask.data.copyOf())
        } else if (mask != null && prev != null) {
            val d = prev.data
            for (i in d.indices) d[i] += alpha * (mask.data[i] - d[i])
        }
        if (visible) {
            if (coverage < offCoverage) misses++ else misses = 0
            if (misses >= framesToHide) {
                visible = false
                hits = 0
                smoothed = null
            }
        } else {
            if (coverage >= onCoverage) hits++ else hits = 0
            if (hits >= framesToShow) {
                visible = true
                misses = 0
            }
        }
        return if (visible) smoothed else null
    }
}
