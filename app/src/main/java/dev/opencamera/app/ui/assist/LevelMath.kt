/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.assist

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Horizon and bubble level from a gravity vector in device sensor coordinates (m/s²). */
object LevelMath {
    /** Above this share of gravity on the z axis the phone counts as facing up or down. */
    const val FLAT_Z = 0.9f
    const val LEVEL_DEG = 1f
    /** Leaving level needs a larger error, so the haptic tick does not chatter at the edge. */
    const val REARM_DEG = 2f

    sealed class Reading {
        abstract val errorDeg: Float
        val level get() = abs(errorDeg) <= LEVEL_DEG

        /**
         * Upright phone. [rollDeg] is the clockwise screen rotation of the horizon line, already
         * reduced to the nearest of portrait/landscape (range -45..45), so it works held any way.
         */
        data class Horizon(val rollDeg: Float) : Reading() {
            override val errorDeg get() = rollDeg
        }

        /** Facing up or down. [x], [y]: bubble offset in -1..1 screen units; [tiltDeg] off flat. */
        data class Bubble(val x: Float, val y: Float, val tiltDeg: Float) : Reading() {
            override val errorDeg get() = tiltDeg
        }
    }

    fun read(gx: Float, gy: Float, gz: Float): Reading? {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 1f) return null
        return if (abs(gz) / g >= FLAT_Z) {
            val tilt = Math.toDegrees(atan2(sqrt(gx * gx + gy * gy).toDouble(), abs(gz).toDouble())).toFloat()
            // Gravity pulls toward the low side; the bubble floats to the high side.
            Reading.Bubble((gx / g * BUBBLE_GAIN).coerceIn(-1f, 1f), (-gy / g * BUBBLE_GAIN).coerceIn(-1f, 1f), tilt)
        } else {
            // Angle of "down" in the screen plane: 0 when the phone is upright in portrait.
            val down = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
            Reading.Horizon(reduce(down))
        }
    }

    /** Maps an angle to the nearest multiple of 90 degrees, returning the remainder in -45..45. */
    fun reduce(deg: Float): Float = deg - (deg / 90f).roundToInt() * 90f

    /** Haptic gate: fires once on entering level, re-arms after leaving by [REARM_DEG]. */
    class Haptic {
        private var armed = true

        fun shouldTick(reading: Reading): Boolean {
            val err = abs(reading.errorDeg)
            if (armed && err <= LEVEL_DEG) {
                armed = false
                return true
            }
            if (!armed && err > REARM_DEG) armed = true
            return false
        }
    }

    private const val BUBBLE_GAIN = 2.5f
}
