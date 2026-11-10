/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.watermark

import kotlin.math.min
import kotlin.math.roundToInt

enum class WatermarkTemplate(val key: String) {
    NONE("none"),
    FILM_BORDER("film_border"),
    EXIF_STRIP("exif_strip");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/** Capture metadata shown in a frame. Any field may be missing. */
data class ExifFields(
    val model: String? = null,
    val focalLengthMm: Float? = null,
    val fNumber: Float? = null,
    val exposureSeconds: Double? = null,
    val iso: Int? = null,
    val dateTime: String? = null,
) {
    /** "27mm  f/1.9  1/120s  ISO 200", skipping fields that are absent or invalid. */
    fun exposureLine(): String = listOfNotNull(
        focalLengthMm?.takeIf { it > 0f }?.let { "${formatNumber(it)}mm" },
        fNumber?.takeIf { it > 0f }?.let { "f/${formatNumber(it)}" },
        exposureSeconds?.takeIf { it > 0.0 }?.let { formatShutter(it) },
        iso?.takeIf { it > 0 }?.let { "ISO $it" },
    ).joinToString("  ")

    companion object {
        fun formatShutter(seconds: Double): String = if (seconds >= 0.3) {
            "${formatNumber(seconds.toFloat())}s"
        } else {
            "1/${(1.0 / seconds).roundToInt()}s"
        }

        fun formatNumber(v: Float): String {
            val r = (v * 10f).roundToInt() / 10f
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }
    }
}

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

enum class Align { START, END }

/** A text slot: baseline position, preferred size and the width it must fit in. */
data class TextSlot(val x: Int, val baseline: Int, val size: Float, val maxWidth: Int, val align: Align, val bold: Boolean)

data class WatermarkLayout(
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** Where the photo goes on the canvas. */
    val image: Box,
    val model: TextSlot,
    val exposure: TextSlot,
    val date: TextSlot,
    /** Thin separator between model and exposure text, or null. */
    val divider: Box?,
) {
    companion object {
        /**
         * Lays out a frame around an upright [width] x [height] photo. All sizes are
         * fractions of the photo's short side so the frame looks the same at any
         * resolution or aspect ratio.
         */
        fun compute(template: WatermarkTemplate, width: Int, height: Int): WatermarkLayout? {
            require(width > 0 && height > 0)
            val s = min(width, height).toFloat()
            fun px(f: Float) = (s * f).roundToInt()
            return when (template) {
                WatermarkTemplate.NONE -> null
                WatermarkTemplate.FILM_BORDER -> {
                    val side = px(0.035f)
                    val bottom = px(0.13f)
                    val cw = width + 2 * side
                    val ch = height + side + bottom
                    val bandTop = side + height
                    val half = (width - px(0.04f)) / 2
                    WatermarkLayout(
                        canvasWidth = cw, canvasHeight = ch,
                        image = Box(side, side, side + width, side + height),
                        model = TextSlot(side, bandTop + (bottom * 0.46f).roundToInt(), s * 0.034f, half, Align.START, true),
                        exposure = TextSlot(cw - side, bandTop + (bottom * 0.46f).roundToInt(), s * 0.026f, half, Align.END, false),
                        date = TextSlot(side, bandTop + (bottom * 0.76f).roundToInt(), s * 0.02f, half, Align.START, false),
                        divider = null,
                    )
                }
                WatermarkTemplate.EXIF_STRIP -> {
                    val strip = px(0.1f)
                    val pad = px(0.04f)
                    val ch = height + strip
                    val mid = width / 2
                    val dividerW = maxOf(1, px(0.002f))
                    WatermarkLayout(
                        canvasWidth = width, canvasHeight = ch,
                        image = Box(0, 0, width, height),
                        model = TextSlot(pad, height + (strip * 0.6f).roundToInt(), s * 0.032f, mid - pad - px(0.02f), Align.START, true),
                        exposure = TextSlot(width - pad, height + (strip * 0.45f).roundToInt(), s * 0.025f, mid - pad - px(0.02f), Align.END, false),
                        date = TextSlot(width - pad, height + (strip * 0.78f).roundToInt(), s * 0.019f, mid - pad - px(0.02f), Align.END, false),
                        divider = Box(mid - dividerW / 2, height + px(0.025f), mid - dividerW / 2 + dividerW, height + strip - px(0.025f)),
                    )
                }
            }
        }
    }
}
