/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.camera.view.PreviewView
import androidx.core.view.children
import dev.opencamera.app.models.GridMode
import kotlin.math.roundToInt

/**
 * A simple view that shows a grid
 */
class GridView(context: Context?, attributeSet: AttributeSet?) : View(context, attributeSet) {
    private val paint = Paint().apply {
        isAntiAlias = true
        strokeWidth = 2F
        style = Paint.Style.STROKE
        color = 0x7FFFFFFF
    }

    var mode: GridMode = GridMode.OFF
        set(value) {
            field = value
            invalidate()
        }
    var previewView: PreviewView? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val previewView = previewView ?: return

        if (mode == GridMode.OFF) return

        // No surface yet (camera rebinding): skip this frame instead of crashing.
        val surface = previewView.children.firstOrNull {
            it is TextureView || it is SurfaceView
        } ?: return

        val width = (surface.scaleX * surface.width).roundToInt().coerceIn(0, previewView.width)
        val height = (surface.scaleY * surface.height).roundToInt().coerceIn(0, previewView.height)

        val wOffset = (this.width - width) / 2F
        val hOffset = (this.height - height) / 2F

        for (l in GridGeometry.lines(mode, width.toFloat(), height.toFloat())) {
            canvas.drawLine(l[0] + wOffset, l[1] + hOffset, l[2] + wOffset, l[3] + hOffset, paint)
        }
    }
}
