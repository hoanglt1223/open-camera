/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.views

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.camera.view.PreviewView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small viewfinder copies without a synchronous full-size readback on the main thread:
 * a SurfaceView preview is copied by an asynchronous [PixelCopy] that completes on a
 * background thread, straight into a downscaled bitmap.
 */
object PreviewFrames {
    private val handler by lazy { Handler(HandlerThread("preview-copy").apply { start() }.looper) }

    /** Size of the viewfinder scaled so its long side is [longSide], or null before layout. */
    fun scaledSize(pv: PreviewView, longSide: Int): Pair<Int, Int>? {
        if (pv.width <= 0 || pv.height <= 0) return null
        val k = longSide.toFloat() / max(pv.width, pv.height)
        return (pv.width * k).roundToInt().coerceAtLeast(1) to (pv.height * k).roundToInt().coerceAtLeast(1)
    }

    /** A new bitmap with the current frame scaled to [longSide], or null when there is no frame. */
    suspend fun copy(pv: PreviewView, longSide: Int): Bitmap? {
        val (w, h) = scaledSize(pv, longSide) ?: return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return if (copyInto(pv, bitmap)) bitmap else null.also { bitmap.recycle() }
    }

    /** Copies the current frame, scaled, into [target]. Call from the main thread. */
    suspend fun copyInto(pv: PreviewView, target: Bitmap): Boolean {
        val children = (0 until pv.childCount).map { pv.getChildAt(it) }
        val surface = children.filterIsInstance<SurfaceView>().firstOrNull()
        if (surface != null) {
            if (surface.holder.surface?.isValid != true) return false
            return suspendCancellableCoroutine { cont ->
                try {
                    PixelCopy.request(surface, target, { result -> cont.resume(result == PixelCopy.SUCCESS) }, handler)
                } catch (e: IllegalArgumentException) {
                    cont.resume(false)
                }
            }
        }
        val texture = children.filterIsInstance<TextureView>().firstOrNull() ?: return false
        return runCatching { texture.getBitmap(target) != null }.getOrDefault(false)
    }
}
