/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

object WatermarkRenderer {
    private val TEXT_PRIMARY = Color.rgb(0x1a, 0x1a, 0x1a)
    private val TEXT_SECONDARY = Color.rgb(0x5c, 0x5c, 0x5c)
    private val DIVIDER = Color.rgb(0xc8, 0xc8, 0xc8)

    /**
     * Draws [template] around [source], which is stored rotated by [rotationDegrees]
     * (the EXIF orientation). The frame is laid out upright, but the returned
     * bitmap keeps the stored orientation so the EXIF orientation tag stays valid.
     * Returns [source] unchanged for [WatermarkTemplate.NONE].
     */
    fun render(source: Bitmap, rotationDegrees: Int, template: WatermarkTemplate, fields: ExifFields): Bitmap {
        val rot = ((rotationDegrees % 360) + 360) % 360
        val swap = rot == 90 || rot == 270
        val uprightW = if (swap) source.height else source.width
        val uprightH = if (swap) source.width else source.height
        val layout = WatermarkLayout.compute(template, uprightW, uprightH) ?: return source

        // Maps upright canvas coordinates onto the stored (rotated) bitmap.
        val m = Matrix().apply { setRotate(-rot.toFloat()) }
        val bounds = RectF(0f, 0f, layout.canvasWidth.toFloat(), layout.canvasHeight.toFloat())
        m.mapRect(bounds)
        m.postTranslate(-bounds.left, -bounds.top)

        val out = Bitmap.createBitmap(bounds.width().toInt(), bounds.height().toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val imageRect = RectF(
            layout.image.left.toFloat(), layout.image.top.toFloat(),
            layout.image.right.toFloat(), layout.image.bottom.toFloat(),
        )
        m.mapRect(imageRect)
        val dst = Rect()
        imageRect.round(dst)
        canvas.drawBitmap(source, null, dst, null)

        canvas.save()
        canvas.concat(m)
        val model = fields.model?.trim().orEmpty()
        val exposure = fields.exposureLine()
        val date = fields.dateTime?.trim().orEmpty()
        val modelPaint = paint(layout.model, TEXT_PRIMARY)
        val exposurePaint = paint(layout.exposure, TEXT_PRIMARY)
        val datePaint = paint(layout.date, TEXT_SECONDARY)
        drawText(canvas, model, layout.model, modelPaint)
        drawText(canvas, exposure, layout.exposure, exposurePaint)
        drawText(canvas, date, layout.date, datePaint)
        layout.divider?.let { d ->
            val rightTextWidth = maxOf(width(exposure, exposurePaint), width(date, datePaint))
            if (rightTextWidth > 0f && model.isNotEmpty()) {
                val gap = layout.exposure.size
                val x = (layout.exposure.x - rightTextWidth - gap).coerceAtLeast(d.left.toFloat())
                canvas.drawRect(x, d.top.toFloat(), x + d.width, d.bottom.toFloat(), Paint().apply { color = DIVIDER })
            }
        }
        canvas.restore()
        return out
    }

    private fun paint(slot: TextSlot, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = slot.size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (slot.bold) Typeface.BOLD else Typeface.NORMAL)
        textAlign = if (slot.align == Align.START) Paint.Align.LEFT else Paint.Align.RIGHT
    }

    private fun width(text: String, paint: Paint) = if (text.isEmpty()) 0f else paint.measureText(text)

    private fun drawText(canvas: Canvas, text: String, slot: TextSlot, paint: Paint) {
        if (text.isEmpty()) return
        val w = paint.measureText(text)
        if (w > slot.maxWidth) paint.textSize = slot.size * slot.maxWidth / w
        canvas.drawText(text, slot.x.toFloat(), slot.baseline.toFloat(), paint)
    }
}
