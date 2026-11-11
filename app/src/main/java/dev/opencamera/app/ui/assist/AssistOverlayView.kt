/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.assist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Level (horizon line, or a bubble when the phone faces up/down) and composition hint arrows.
 * Pure drawing; state comes from [LevelSensor] and the composition analysis.
 */
class AssistOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(200, 255, 255, 255)
    }
    private val arrowPath = Path()

    var level: LevelMath.Reading? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var hints: List<CompositionHints.Hint> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        level?.let { drawLevel(canvas, it) }
        for (h in hints) drawHint(canvas, h)
    }

    private fun drawLevel(canvas: Canvas, r: LevelMath.Reading) {
        val color = if (r.level) LEVEL_COLOR else Color.argb(200, 255, 255, 255)
        line.color = color
        dot.color = color
        val cx = width / 2f
        val cy = height / 2f
        when (r) {
            is LevelMath.Reading.Horizon -> {
                val half = min(width, height) * 0.18f
                val gap = 10f * density
                // Fixed reference stubs, plus the world horizon rotated against them.
                canvas.drawLine(cx - half - 3 * gap, cy, cx - half - gap, cy, line)
                canvas.drawLine(cx + half + gap, cy, cx + half + 3 * gap, cy, line)
                canvas.save()
                canvas.rotate(r.rollDeg, cx, cy)
                canvas.drawLine(cx - half, cy, cx + half, cy, line)
                canvas.restore()
            }
            is LevelMath.Reading.Bubble -> {
                val radius = 28f * density
                canvas.drawCircle(cx, cy, radius, line)
                canvas.drawCircle(cx, cy, 4f * density, line)
                canvas.drawCircle(cx + r.x * radius, cy + r.y * radius, 7f * density, dot)
            }
        }
    }

    private fun drawHint(canvas: Canvas, h: CompositionHints.Hint) {
        val w = width.toFloat()
        val ht = height.toFloat()
        when (h) {
            is CompositionHints.Hint.Move -> {
                val x0 = h.fromX * w
                val y0 = h.fromY * ht
                val x1 = h.toX * w
                val y1 = h.toY * ht
                if (hypot(x1 - x0, y1 - y0) < 12f * density) return
                arrow(canvas, x0, y0, x1, y1)
            }
            is CompositionHints.Hint.Straighten -> {
                // Curved-ish cue near the top: an arrow in the direction to rotate the phone.
                val y = ht * 0.12f
                val len = 36f * density
                val dir = if (h.clockwise) 1f else -1f
                arrow(canvas, w / 2f - dir * len, y, w / 2f + dir * len, y)
            }
            is CompositionHints.Hint.Headroom -> {
                val x = w / 2f
                arrow(canvas, x, ht * 0.10f, x, ht * 0.10f - 28f * density)
            }
        }
    }

    private fun arrow(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        canvas.drawLine(x0, y0, x1, y1, hint)
        val a = atan2(y1 - y0, x1 - x0)
        val head = 10f * density
        arrowPath.reset()
        arrowPath.moveTo(x1 - head * kotlin.math.cos(a - 0.5f), y1 - head * kotlin.math.sin(a - 0.5f))
        arrowPath.lineTo(x1, y1)
        arrowPath.lineTo(x1 - head * kotlin.math.cos(a + 0.5f), y1 - head * kotlin.math.sin(a + 0.5f))
        canvas.drawPath(arrowPath, hint)
    }

    private companion object {
        val LEVEL_COLOR = Color.rgb(76, 217, 100)
    }
}
