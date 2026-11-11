/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.assist

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns the composition analysis and the level into a few subtle hints: move the subject toward
 * its thirds point, straighten the horizon, leave headroom. Pure, unit tested.
 */
object CompositionHints {
    sealed class Hint {
        /** Normalized view coordinates: arrow from the subject to where it should go. */
        data class Move(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : Hint()
        data class Straighten(val clockwise: Boolean) : Hint()
        data object Headroom : Hint()
    }

    const val MOVE_MIN = 0.08f
    const val TILT_MIN_DEG = 3f
    const val HEADROOM_MIN = 0.06f

    /**
     * [subject]/[target]: normalized centroid and thirds point, or null without a subject.
     * [subjectTop]: normalized top edge of the subject, if known. [rollDeg]: horizon error.
     */
    fun compute(
        subjectX: Float?,
        subjectY: Float?,
        targetX: Float?,
        targetY: Float?,
        subjectTop: Float?,
        rollDeg: Float?,
    ): List<Hint> = buildList {
        if (rollDeg != null && abs(rollDeg) >= TILT_MIN_DEG) {
            // The drawn horizon is rotated by rollDeg; turning the phone the same way levels it.
            add(Hint.Straighten(clockwise = rollDeg > 0))
        }
        if (subjectX != null && subjectY != null && targetX != null && targetY != null &&
            hypot(targetX - subjectX, targetY - subjectY) >= MOVE_MIN
        ) {
            add(Hint.Move(subjectX, subjectY, targetX, targetY))
        }
        if (subjectTop != null && subjectTop < HEADROOM_MIN) add(Hint.Headroom)
    }
}
