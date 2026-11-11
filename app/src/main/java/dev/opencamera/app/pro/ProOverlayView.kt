/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Draws focus peaking, zebra stripes, a luma histogram and the light meter over the preview.
 */
class ProOverlayView(context: Context) : View(context) {
    class Frame(
        val mask: Bitmap?,
        val histogram: IntArray?,
        val meterText: String?,
    )

    private var frame: Frame? = null
    private val density = resources.displayMetrics.density
    private val maskPaint = Paint().apply { isFilterBitmap = false }
    private val histBackground = Paint().apply { color = 0x66000000 }
    private val histPaint = Paint().apply { color = 0xCCFFFFFF.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * density
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val dst = Rect()
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(newFrame: Frame?) {
        frame?.mask?.recycle()
        frame = newFrame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return

        f.mask?.let {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(it, null, dst, maskPaint)
        }

        val margin = 12 * density
        f.histogram?.let { hist ->
            val w = 128 * density
            val h = 56 * density
            box.set(width - margin - w, margin + 56 * density, width - margin, margin + 56 * density + h)
            canvas.drawRoundRect(box, 6 * density, 6 * density, histBackground)
            val max = (hist.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
            val barW = w / hist.size
            hist.forEachIndexed { i, v ->
                val barH = (v / max) * (h - 4 * density)
                canvas.drawRect(
                    box.left + i * barW, box.bottom - 2 * density - barH,
                    box.left + (i + 1) * barW, box.bottom - 2 * density, histPaint,
                )
            }
        }

        f.meterText?.let {
            canvas.drawText(it, margin, height - margin, textPaint)
        }
    }
}
