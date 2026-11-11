/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

/**
 * Draws mode guides over the preview: the document outline, the time-freeze line, the
 * panorama sweep bar and the audio meter. Frame coordinates are mapped like FILL_CENTER.
 */
class GuideOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.CYAN
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val path = Path()

    /** Document corners in frame pixels (TL, TR, BR, BL) and the frame size. */
    var quad: FloatArray? = null
    var frameWidth = 0
    var frameHeight = 0

    /** Time-freeze line, 0..1 across the frame, or negative when hidden. */
    var freezeLine = -1f

    /** Panorama sweep: fraction swept, pitch error in degrees, or null when hidden. */
    var sweep: Float? = null
    var pitchDeg = 0f

    /** Audio level 0..1, or negative when hidden. */
    var audioLevel = -1f

    fun refresh() = postInvalidateOnAnimation()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fw = frameWidth
        val fh = frameHeight
        val scale = if (fw > 0 && fh > 0) max(width.toFloat() / fw, height.toFloat() / fh) else 1f
        val ox = (width - fw * scale) / 2f
        val oy = (height - fh * scale) / 2f

        quad?.let { q ->
            path.reset()
            for (i in 0..3) {
                val x = ox + q[i * 2] * scale
                val y = oy + q[i * 2 + 1] * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, stroke)
        }

        if (freezeLine >= 0f && fw > 0) {
            val x = ox + freezeLine * fw * scale
            canvas.drawLine(x, 0f, x, height.toFloat(), stroke)
        }

        sweep?.let { s ->
            val barW = width * 0.7f
            val left = (width - barW) / 2f
            val top = height * 0.5f
            val h = 10 * density
            canvas.drawRect(left, top - h, left + barW, top + h, dim)
            canvas.drawRect(left, top - h, left + barW * s.coerceIn(0f, 1f), top + h, fill)
            val py = top - pitchDeg * 6 * density
            canvas.drawLine(left, py, left + barW, py, stroke)
        }

        if (audioLevel >= 0f) {
            val barH = height * 0.25f
            val left = width - 14 * density
            val bottom = height * 0.62f
            canvas.drawRect(left, bottom - barH, left + 6 * density, bottom, dim)
            canvas.drawRect(left, bottom - barH * audioLevel.coerceIn(0f, 1f), left + 6 * density, bottom, fill)
        }
    }
}
