/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.retouch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import dev.opencamera.pipeline.retouch.InputKind
import dev.opencamera.pipeline.retouch.NPoint
import dev.opencamera.pipeline.retouch.Stroke
import kotlin.math.max

/**
 * The editing canvas: shows the working bitmap and collects input for the active tool in
 * normalized image coordinates: brush strokes (drawn as a red overlay) or single taps.
 */
@SuppressLint("ViewConstructor")
class BrushCanvasView(context: Context, private val onTap: (NPoint) -> Unit) : AppCompatImageView(context) {
    var mode = InputKind.NONE
        set(value) {
            field = value
            invalidate()
        }

    /** Brush radius as a fraction of the image's long side. */
    var brushRadius = 0.03f
    var eraseBrush = false
    val strokes = ArrayList<Stroke>()
    private var current: MutableList<NPoint>? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 40, 40)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    fun clearStrokes() {
        strokes.clear()
        invalidate()
    }

    private fun toImage(x: Float, y: Float): NPoint? {
        val d = drawable ?: return null
        val inv = Matrix()
        if (!imageMatrix.invert(inv)) return null
        val p = floatArrayOf(x - paddingLeft, y - paddingTop)
        inv.mapPoints(p)
        val u = p[0] / d.intrinsicWidth
        val v = p[1] / d.intrinsicHeight
        return if (u in -0.05f..1.05f && v in -0.05f..1.05f) NPoint(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (mode) {
            InputKind.NONE -> return false
            InputKind.TAP -> {
                if (e.action == MotionEvent.ACTION_UP) toImage(e.x, e.y)?.let(onTap)
                return true
            }
            InputKind.BRUSH -> {
                val p = toImage(e.x, e.y) ?: return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> current = mutableListOf(p)
                    MotionEvent.ACTION_MOVE -> current?.add(p)
                    MotionEvent.ACTION_UP -> {
                        current?.let { strokes += Stroke(it.toList(), brushRadius, eraseBrush) }
                        current = null
                    }
                    MotionEvent.ACTION_CANCEL -> current = null
                }
                invalidate()
                return true
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = drawable ?: return
        if (strokes.isEmpty() && current == null) return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.concat(imageMatrix)
        val long = max(d.intrinsicWidth, d.intrinsicHeight).toFloat()
        fun draw(points: List<NPoint>, radius: Float, erase: Boolean) {
            paint.strokeWidth = radius * long * 2
            paint.color = if (erase) Color.argb(110, 40, 160, 255) else Color.argb(110, 255, 40, 40)
            for (i in 1 until points.size) {
                canvas.drawLine(points[i - 1].x * d.intrinsicWidth, points[i - 1].y * d.intrinsicHeight, points[i].x * d.intrinsicWidth, points[i].y * d.intrinsicHeight, paint)
            }
            if (points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(points[0].x * d.intrinsicWidth, points[0].y * d.intrinsicHeight, radius * long, paint)
                paint.style = Paint.Style.STROKE
            }
        }
        strokes.forEach { draw(it.points, it.radius, it.erase) }
        current?.let { draw(it, brushRadius, eraseBrush) }
        canvas.restore()
    }
}

/**
 * Before/after view: the result on the right of a draggable divider, the original on the left.
 * Double tap toggles 2x zoom around the tap point.
 */
@SuppressLint("ViewConstructor")
class BeforeAfterView(context: Context, private val before: Bitmap, private val after: Bitmap) : View(context) {
    private var divider = 0.5f
    private var zoom = 1f
    private var focusX = 0.5f
    private var focusY = 0.5f
    private var lastTap = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint().apply { color = Color.WHITE; strokeWidth = 4f }

    init {
        contentDescription = "before / after"
    }

    private fun frame(): RectF {
        val k = minOf(width.toFloat() / after.width, height.toFloat() / after.height)
        val w = after.width * k
        val h = after.height * k
        val base = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        if (zoom == 1f) return base
        val fx = focusX * width
        val fy = focusY * height
        return RectF(fx + (base.left - fx) * zoom, fy + (base.top - fy) * zoom, fx + (base.right - fx) * zoom, fy + (base.bottom - fy) * zoom)
    }

    override fun onDraw(canvas: Canvas) {
        val r = frame()
        val x = width * divider
        canvas.save()
        canvas.clipRect(0f, 0f, x, height.toFloat())
        canvas.drawBitmap(before, null, r, paint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(x, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(after, null, r, paint)
        canvas.restore()
        canvas.drawLine(x, 0f, x, height.toFloat(), line)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val now = e.eventTime
            if (now - lastTap < 300) {
                zoom = if (zoom > 1f) 1f else 2f
                focusX = (e.x / width).coerceIn(0f, 1f)
                focusY = (e.y / height).coerceIn(0f, 1f)
            }
            lastTap = now
        }
        divider = (e.x / width).coerceIn(0f, 1f)
        invalidate()
        return true
    }
}
