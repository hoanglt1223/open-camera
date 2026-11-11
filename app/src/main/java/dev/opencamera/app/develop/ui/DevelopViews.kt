/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import dev.opencamera.app.R
import dev.opencamera.app.develop.auto.Histogram
import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.Param
import dev.opencamera.pipeline.looks.ToneTable
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/**
 * One labelled slider. Double-tap the label (or use the accessibility "Reset" action) to return
 * to the default. Values are announced with locale formatting; D-pad and volume keys step it.
 */
@SuppressLint("ViewConstructor")
class ParamSlider(
    context: Context,
    val param: Param,
    label: String,
    private val onChange: (Float, Boolean) -> Unit,
    private val onRelease: () -> Unit,
) : LinearLayout(context) {
    private val theme = DevelopTheme.current
    private val name = TextView(context)
    private val value = TextView(context)
    val slider = Slider(context)
    private val fmt = NumberFormat.getNumberInstance().apply { maximumFractionDigits = if (param.step < 1f) 2 else 0 }
    private var updating = false

    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        name.text = label
        name.setTextColor(theme.onSurface)
        name.minHeight = context.dp(32f).toInt()
        name.gravity = Gravity.CENTER_VERTICAL
        value.setTextColor(theme.onSurfaceMuted)
        value.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        row.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                reset()
                return true
            }
        })
        row.setOnTouchListener { v, e -> detector.onTouchEvent(e).also { if (e.action == MotionEvent.ACTION_UP) v.performClick() } }
        row.contentDescription = context.getString(R.string.develop_reset_value, label)
        addView(row)
        slider.valueFrom = param.min
        slider.valueTo = param.max
        slider.stepSize = 0f
        slider.value = param.default
        slider.setLabelFormatter { fmt.format(it) }
        slider.minimumHeight = context.dp(48f).toInt()
        slider.trackActiveTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        slider.thumbTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        slider.addOnChangeListener { _, v, fromUser ->
            value.text = fmt.format(v)
            slider.contentDescription = context.getString(R.string.develop_slider_desc, label, fmt.format(v))
            if (!updating) onChange(snap(v), fromUser)
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) = Unit
            override fun onStopTrackingTouch(s: Slider) = onRelease()
        })
        addView(slider, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        set(param.default)
    }

    private fun snap(v: Float) = if (param.step >= 1f) v.roundToInt().toFloat() else v

    fun set(v: Float) {
        updating = true
        slider.value = v.coerceIn(param.min, param.max)
        value.text = fmt.format(v)
        updating = false
    }

    fun reset() {
        set(param.default)
        onChange(param.default, true)
        onRelease()
    }

    /** Volume keys and D-pad: one step of 1% of the range. */
    fun nudge(direction: Int) {
        val step = max(param.step, (param.max - param.min) / 100f)
        val v = (slider.value + direction * step).coerceIn(param.min, param.max)
        set(v)
        onChange(v, true)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.develop_action_reset, context.getString(R.string.develop_reset)))
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == R.id.develop_action_reset) { reset(); return true }
        return super.performAccessibilityAction(action, arguments)
    }
}

/** RGB + luminance histogram with clipping indicators. */
class HistogramView(context: Context) : View(context) {
    var histogram: Histogram? = null
        set(v) { field = v; invalidate(); contentDescription = v?.let { "Histogram, ${(it.clippedHighlights * 100).roundToInt()}% highlights clipped" } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val h = histogram ?: return
        canvas.drawColor(0x99000000.toInt())
        val m = h.max.toFloat()
        paint.style = Paint.Style.FILL
        for ((arr, color) in listOf(h.r to 0x88ff4040.toInt(), h.g to 0x8840ff40.toInt(), h.b to 0x884080ff.toInt(), h.l to 0x66ffffff)) {
            path.reset()
            path.moveTo(0f, height.toFloat())
            for (i in 0 until 256) path.lineTo(i * width / 255f, height - height * (arr[i] / m).coerceAtMost(1f) * 0.95f)
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
            paint.color = color
            canvas.drawPath(path, paint)
        }
    }
}

/**
 * Tone curve editor for one channel: drag points, tap to add, long-press to remove. Drawn as
 * the monotone spline the renderer uses.
 */
class CurveView(context: Context, private val onChange: (List<CurvePoint>, Boolean) -> Unit) : View(context) {
    var points: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        set(v) { field = v; invalidate(); updateDescription() }
    var color = Color.WHITE
        set(v) { field = v; invalidate() }
    var channelName = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = -1
    private val theme = DevelopTheme.current

    init {
        minimumHeight = context.dp(220f).toInt()
        isFocusable = true
    }

    private fun updateDescription() {
        contentDescription = context.getString(R.string.develop_curve_desc, channelName, points.size)
    }

    private fun toView(p: CurvePoint) = floatArrayOf(pad + p.x * (width - 2 * pad), height - pad - p.y * (height - 2 * pad))
    private val pad get() = context.dp(12f)

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = theme.onSurfaceMuted
        paint.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        for (i in 1..3) {
            val x = pad + i * (width - 2 * pad) / 4f
            val y = pad + i * (height - 2 * pad) / 4f
            canvas.drawLine(x, pad, x, height - pad, paint)
            canvas.drawLine(pad, y, width - pad, y, paint)
        }
        paint.pathEffect = null
        canvas.drawRect(pad, pad, width - pad, height - pad, paint)
        val table = ToneTable.build(points.map { it.x to it.y }, 0f)
        val path = Path()
        for (i in table.indices) {
            val v = toView(CurvePoint(i / 255f, table[i]))
            if (i == 0) path.moveTo(v[0], v[1]) else path.lineTo(v[0], v[1])
        }
        paint.color = color
        paint.strokeWidth = context.dp(2f)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        for (p in points) {
            val v = toView(p)
            canvas.drawCircle(v[0], v[1], context.dp(6f), paint)
        }
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val i = nearest(e.x, e.y)
            if (i > 0 && i < points.size - 1) {
                points = points.filterIndexed { k, _ -> k != i }
                dragging = -1
                onChange(points, true)
            }
        }
    })

    private fun nearest(x: Float, y: Float): Int {
        var best = -1
        var bd = context.dp(28f)
        points.forEachIndexed { i, p ->
            val v = toView(p)
            val d = hypot(v[0] - x, v[1] - y)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        val px = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        val py = (1f - (e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = nearest(e.x, e.y)
                if (dragging < 0 && points.size < 16) {
                    points = (points + CurvePoint(px, py)).sortedBy { it.x }
                    dragging = points.indexOfFirst { it.x == px }
                    onChange(points, false)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                val lo = if (dragging == 0) 0f else points[dragging - 1].x + 0.01f
                val hi = if (dragging == points.size - 1) 1f else points[dragging + 1].x - 0.01f
                val x = if (dragging == 0 || dragging == points.size - 1) points[dragging].x else px.coerceIn(lo, hi)
                points = points.toMutableList().also { it[dragging] = CurvePoint(x, py) }
                onChange(points, false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                onChange(points, true)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * Colour grading wheel: angle = hue, distance = saturation. TalkBack users get exact entry via
 * the "Enter values" button next to it.
 */
class ColorWheelView(context: Context, private val onChange: (Float, Float, Boolean) -> Unit) : View(context) {
    var hue = 0f
        private set
    var sat = 0f
        private set
    var label = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val theme = DevelopTheme.current

    init {
        minimumHeight = context.dp(180f).toInt()
        isFocusable = true
    }

    fun set(h: Float, s: Float) {
        hue = h; sat = s
        contentDescription = context.getString(R.string.develop_wheel_desc, label, h.roundToInt(), s.roundToInt())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f - context.dp(8f)
        val cx = width / 2f; val cy = height / 2f
        val colors = IntArray(7) { Color.HSVToColor(floatArrayOf((360f - it * 60f) % 360f, 1f, 1f)) }
        paint.shader = SweepGradient(cx, cy, colors, null)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = RadialGradient(cx, cy, r, theme.surface, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
        val a = Math.toRadians(hue.toDouble())
        val d = r * sat / 100f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(2f)
        paint.color = Color.WHITE
        canvas.drawCircle(cx + (cos(a) * d).toFloat(), cy - (sin(a) * d).toFloat(), context.dp(8f), paint)
        paint.style = Paint.Style.FILL
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val r = min(width, height) / 2f - context.dp(8f)
        val dx = e.x - width / 2f
        val dy = height / 2f - e.y
        val h = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
        val s = (hypot(dx, dy) / r * 100f).coerceIn(0f, 100f)
        set(h, s)
        val done = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        onChange(h, s, done)
        if (done) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Crop frame, gradient handles, brush cursor and the before/after divider over the preview. */
class PreviewOverlay(context: Context) : View(context) {
    /** Image rectangle on screen. */
    val image = RectF()
    var crop: RectF? = null
    var split = -1f
    var line: FloatArray? = null
    var ellipse: FloatArray? = null
    var cursor: FloatArray? = null
    var maskTint: android.graphics.Bitmap? = null
    var hint: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val theme = DevelopTheme.current

    override fun onDraw(canvas: Canvas) {
        maskTint?.let { canvas.drawBitmap(it, null, image, Paint(Paint.FILTER_BITMAP_FLAG)) }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1.5f)
        crop?.let { c ->
            val r = RectF(image.left + c.left * image.width(), image.top + c.top * image.height(),
                image.left + c.right * image.width(), image.top + c.bottom * image.height())
            paint.color = 0x99000000.toInt(); paint.style = Paint.Style.FILL
            canvas.drawRect(image.left, image.top, image.right, r.top, paint)
            canvas.drawRect(image.left, r.bottom, image.right, image.bottom, paint)
            canvas.drawRect(image.left, r.top, r.left, r.bottom, paint)
            canvas.drawRect(r.right, r.top, image.right, r.bottom, paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.WHITE
            canvas.drawRect(r, paint)
            for (i in 1..2) {
                canvas.drawLine(r.left + r.width() * i / 3, r.top, r.left + r.width() * i / 3, r.bottom, paint)
                canvas.drawLine(r.left, r.top + r.height() * i / 3, r.right, r.top + r.height() * i / 3, paint)
            }
            paint.strokeWidth = context.dp(4f); paint.color = theme.accent
            val k = context.dp(16f)
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
                canvas.drawLine(x, y, x + if (x == r.left) k else -k, y, paint)
                canvas.drawLine(x, y, x, y + if (y == r.top) k else -k, paint)
            }
            paint.strokeWidth = context.dp(1.5f)
        }
        line?.let { l ->
            paint.color = theme.accent
            canvas.drawLine(l[0], l[1], l[2], l[3], paint)
            canvas.drawCircle(l[0], l[1], context.dp(8f), paint)
            canvas.drawCircle(l[2], l[3], context.dp(5f), paint)
        }
        ellipse?.let { e ->
            paint.color = theme.accent
            canvas.drawOval(e[0] - e[2], e[1] - e[3], e[0] + e[2], e[1] + e[3], paint)
        }
        cursor?.let { c ->
            paint.color = Color.WHITE
            canvas.drawCircle(c[0], c[1], c[2], paint)
        }
        if (split in 0f..1f) {
            val x = image.left + split * image.width()
            paint.color = Color.WHITE; paint.strokeWidth = context.dp(2f)
            canvas.drawLine(x, image.top, x, image.bottom, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, image.centerY(), context.dp(10f), paint)
        }
        hint?.let {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = context.dp(14f)
            paint.setShadowLayer(4f, 0f, 0f, Color.BLACK)
            canvas.drawText(it, context.dp(12f), context.dp(24f), paint)
            paint.clearShadowLayer()
        }
    }

    /** Hit test for crop handles: 0..3 corners (tl, tr, bl, br), 4 = move, -1 none. */
    fun cropHandle(x: Float, y: Float): Int {
        val c = crop ?: return -1
        val r = RectF(image.left + c.left * image.width(), image.top + c.top * image.height(),
            image.left + c.right * image.width(), image.top + c.bottom * image.height())
        val k = context.dp(32f)
        listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).forEachIndexed { i, (cx, cy) ->
            if (abs(x - cx) < k && abs(y - cy) < k) return i
        }
        return if (r.contains(x, y)) 4 else -1
    }
}
