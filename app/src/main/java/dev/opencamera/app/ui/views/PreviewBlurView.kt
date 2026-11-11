/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.appcompat.widget.AppCompatImageView
import androidx.camera.view.PreviewView
import dev.opencamera.app.ext.stackBlur

/**
 * Display a blurred viewfinder snapshot during camera rebind.
 */
class PreviewBlurView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    var previewView: PreviewView? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(RenderEffect.createBlurEffect(RADIUS, RADIUS, Shader.TileMode.REPEAT))
        }
    }

    /**
     * Shows a blurred snapshot of the viewfinder. The snapshot is taken at a quarter of the
     * view size (it is blurred anyway) and, for a SurfaceView preview, copied asynchronously,
     * so a rebind does not stall the UI thread on a full-size synchronous PixelCopy.
     */
    fun freeze() {
        val pv = previewView?.takeUnless { it.height <= 0 || it.width <= 0 }
        if (pv == null) {
            setImageResource(android.R.color.black)
            return
        }
        val w = (pv.width / SNAPSHOT_DIVISOR).coerceAtLeast(1)
        val h = (pv.height / SNAPSHOT_DIVISOR).coerceAtLeast(1)
        val child = (0 until pv.childCount).map { pv.getChildAt(it) }
        val texture = child.filterIsInstance<TextureView>().firstOrNull()
        val surface = child.filterIsInstance<SurfaceView>().firstOrNull()
        runCatching {
            when {
                texture != null -> show(texture.getBitmap(w, h))
                surface != null && surface.holder.surface?.isValid == true -> {
                    setImageResource(android.R.color.black)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val generation = ++requests
                    PixelCopy.request(surface, bmp, { result ->
                        if (result == PixelCopy.SUCCESS && generation == requests) show(bmp)
                    }, handler)
                }
                else -> show(pv.bitmap)
            }
        }.onFailure { setImageResource(android.R.color.black) }
    }

    private var requests = 0
    private val handler = Handler(Looper.getMainLooper())

    private fun show(bitmap: Bitmap?) {
        if (bitmap == null) {
            setImageResource(android.R.color.black)
            return
        }
        setImageBitmap(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) bitmap else bitmap.stackBlur((RADIUS / SNAPSHOT_DIVISOR).toInt().coerceAtLeast(2))
        )
    }
    companion object {
        private const val RADIUS = 25f
        private const val SNAPSHOT_DIVISOR = 4
    }
}
