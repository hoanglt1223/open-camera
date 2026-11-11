/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.views

import dev.opencamera.app.models.GridMode
import kotlin.math.min

/** Grid lines as (x0, y0, x1, y1) in a [width] x [height] frame starting at (0, 0). */
object GridGeometry {
    /** 1/φ² and 1/φ: the golden ratio divisions of a side. */
    const val PHI_MINOR = 0.381966f
    const val PHI_MAJOR = 0.618034f

    fun lines(mode: GridMode, width: Float, height: Float): List<FloatArray> {
        if (width <= 0f || height <= 0f) return emptyList()
        return when (mode) {
            GridMode.OFF -> emptyList()
            GridMode.ON_3 -> divisions(listOf(1 / 3f, 2 / 3f), 0f, 0f, width, height)
            GridMode.ON_4 -> divisions(listOf(0.25f, 0.5f, 0.75f), 0f, 0f, width, height)
            GridMode.ON_GOLDEN_RATIO -> divisions(listOf(PHI_MINOR, PHI_MAJOR), 0f, 0f, width, height)
            GridMode.DIAGONAL -> divisions(listOf(1 / 3f, 2 / 3f), 0f, 0f, width, height) + listOf(
                floatArrayOf(0f, 0f, width, height),
                floatArrayOf(width, 0f, 0f, height),
            )
            GridMode.SQUARE -> {
                val side = min(width, height)
                val x = (width - side) / 2f
                val y = (height - side) / 2f
                listOf(
                    floatArrayOf(x, y, x + side, y),
                    floatArrayOf(x + side, y, x + side, y + side),
                    floatArrayOf(x + side, y + side, x, y + side),
                    floatArrayOf(x, y + side, x, y),
                ) + divisions(listOf(1 / 3f, 2 / 3f), x, y, side, side)
            }
        }
    }

    private fun divisions(fractions: List<Float>, x: Float, y: Float, w: Float, h: Float) =
        fractions.flatMap { f ->
            listOf(
                floatArrayOf(x + w * f, y, x + w * f, y + h),
                floatArrayOf(x, y + h * f, x + w, y + h * f),
            )
        }
}
