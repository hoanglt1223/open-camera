/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Draws the exposure aids over the preview: zebra, focus peaking and false color through one
 * AGSL shader over a small preview copy, plus the luma histogram and the light meter.
 */
class ProOverlayView(context: Context) : View(context) {
    class Frame(
        /** Preview copy for the shader; never recycled here, the producer reuses it. */
        val source: Bitmap?,
        /** Where [source] lies in this view: the preview surface is cropped and centered by PreviewView. */
        val sourceRect: RectF?,
        val aids: ExposureAids.Active,
        val zebraPercent: Int,
        val histogram: IntArray?,
        val meterText: String?,
    )

    /** Called on the main thread when the shader cannot be built or drawn. */
    var onGpuFailure: ((ExposureAids.Unavailable) -> Unit)? = null

    private var frame: Frame? = null
    private val density = resources.displayMetrics.density
    private val histBackground = Paint().apply { color = 0x66000000 }
    private val histPaint = Paint().apply { color = 0xCCFFFFFF.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * density
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }
    private val box = RectF()
    private val matrix = Matrix()
    private var gpu: Gpu? = null
    private var gpuFailed = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(newFrame: Frame?) {
        frame = newFrame
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        val source = f.source
        if (f.aids.shader && source != null && !gpuFailed && Build.VERSION.SDK_INT >= ExposureAids.MIN_GPU_SDK) {
            drawAids(canvas, f, source)
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
            // Top left, beside the histogram: the bottom of the preview holds the controls.
            canvas.drawText(it, margin, margin + 56 * density + textPaint.textSize, textPaint)
        }
    }

    @RequiresApi(ExposureAids.MIN_GPU_SDK)
    private fun drawAids(canvas: Canvas, f: Frame, source: Bitmap) {
        val g = gpu ?: try {
            Gpu().also { gpu = it }
        } catch (e: RuntimeException) {
            // RuntimeShader throws IllegalArgumentException when the AGSL does not compile.
            fail(ExposureAids.Unavailable.SHADER_COMPILE, e)
            return
        }
        try {
            g.draw(canvas, f, source)
        } catch (e: RuntimeException) {
            fail(ExposureAids.Unavailable.DRAW, e)
        }
    }

    private fun fail(reason: ExposureAids.Unavailable, e: Exception) {
        Log.w(LOG_TAG, "Exposure aids disabled: $reason", e)
        gpuFailed = true
        gpu = null
        post { onGpuFailure?.invoke(reason) }
    }

    @RequiresApi(ExposureAids.MIN_GPU_SDK)
    private inner class Gpu {
        private val shader = RuntimeShader(ExposureAids.SHADER)
        private val paint = Paint().apply { shader = this@Gpu.shader }
        private var bound: Frame? = null

        fun draw(canvas: Canvas, f: Frame, source: Bitmap) {
            val dst = f.sourceRect ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
            val sx = dst.width() / source.width
            val sy = dst.height() / source.height
            // The producer rewrites the same two bitmaps; a new BitmapShader per frame is cheap
            // and makes sure the GPU texture is refreshed with the new pixels.
            if (f !== bound) {
                bound = f
                matrix.setScale(sx, sy)
                matrix.postTranslate(dst.left, dst.top)
                val input = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                    setLocalMatrix(matrix)
                }
                shader.setInputShader("frame", input)
            }
            shader.setFloatUniform("texel", sx, sy)
            shader.setFloatUniform("zebraOn", if (f.aids.zebra) 1f else 0f)
            shader.setFloatUniform("zebraLevel", ExposureAids.zebraThresholdLuma(f.zebraPercent).toFloat())
            shader.setFloatUniform("stripe", STRIPE_DP * density)
            shader.setFloatUniform("peakingOn", if (f.aids.peaking) 1f else 0f)
            shader.setFloatUniform("peakingThreshold", ExposureAids.PEAKING_THRESHOLD.toFloat())
            shader.setFloatUniform("falseColorOn", if (f.aids.falseColor) 1f else 0f)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private companion object {
        val LOG_TAG = ProOverlayView::class.simpleName!!
        const val STRIPE_DP = 4f
    }
}
