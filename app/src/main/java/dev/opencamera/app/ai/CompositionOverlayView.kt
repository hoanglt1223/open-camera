/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.opencamera.ml.tasks.Composition

/** Draws the composition hint: subject, nearest thirds point, an arrow between them and a crop. */
class CompositionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.argb(220, 255, 214, 0)
    }
    private val crop = Paint(stroke).apply {
        color = Color.argb(160, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 214, 0) }

    var suggestion: Composition.Suggestion? = null
        set(value) {
            field = value
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val s = suggestion ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val tx = s.targetX * w
        val ty = s.targetY * h
        canvas.drawCircle(tx, ty, 14f * density, stroke)
        if (s.aligned) {
            canvas.drawCircle(tx, ty, 6f * density, fill)
            return
        }
        val sx = s.subjectX * w
        val sy = s.subjectY * h
        canvas.drawCircle(sx, sy, 5f * density, fill)
        canvas.drawLine(sx, sy, tx, ty, stroke)
        s.crop?.let { c -> canvas.drawRect(c.left * w, c.top * h, c.right * w, c.bottom * h, crop) }
    }
}
