/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import android.content.Context

/**
 * Puts the look renderer between the camera and the preview view without a CameraX
 * `CameraEffect`, so the look can stay live next to RAW capture (CameraX refuses effects in a
 * session with a RAW `ImageCapture`) and switching looks never changes the session.
 *
 * Every camera request gets a twin request with the same size, camera and transformation info
 * that is handed to [target] (the preview view). The view's surface becomes a GL output of
 * [processor] and the camera writes into the processor's input texture.
 *
 * Any failure hands the camera request to [target] unchanged: plain preview, never a crash.
 */
class LookPreviewSurfaceProvider(
    context: Context,
    private val target: Preview.SurfaceProvider,
    private val processor: () -> LookSurfaceProcessor?,
    private val onFailure: (Throwable) -> Unit,
) : Preview.SurfaceProvider {
    private val main = ContextCompat.getMainExecutor(context)

    /** Cleared after a failure; later requests go straight to [target]. */
    @Volatile
    var enabled = true

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val p = if (usesRenderer(enabled, request.dynamicRange == DynamicRange.SDR)) processor() else null
        if (p == null) {
            target.onSurfaceRequested(request)
            return
        }
        try {
            bridge(request, p)
        } catch (e: RuntimeException) {
            fail(e)
            target.onSurfaceRequested(request)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun bridge(request: SurfaceRequest, p: LookSurfaceProcessor) {
        val size = request.resolution
        // The view invalidating its surface asks the camera for a new request, as it would directly.
        val twin = SurfaceRequest(size, request.camera, request.dynamicRange, request.expectedFrameRate) {
            request.invalidate()
        }
        request.setTransformationInfoListener(main) { twin.updateTransformationInfo(it) }
        request.addRequestCancellationListener(main) { twin.willNotProvideSurface() }
        target.onSurfaceRequested(twin)
        val future = twin.deferrableSurface.surface
        future.addListener({
            val surface = runCatching { future.get() }.getOrNull()
            if (surface == null) {
                // The view gave up (or the request was cancelled before it answered).
                request.willNotProvideSurface()
                twin.deferrableSurface.close()
                return@addListener
            }
            val key = Any()
            p.addOutput(key, surface, size.width, size.height) { e ->
                main.execute {
                    fail(e)
                    request.invalidate()
                }
            }
            p.provideInput(request) {
                p.removeOutput(key)
                // Releases the view's surface (its result listener runs once the camera is done).
                main.execute { twin.deferrableSurface.close() }
            }
            Log.i(TAG, "Look preview ${size.width}x${size.height}")
        }, main)
    }

    private fun fail(e: Throwable) {
        Log.w(TAG, "Look preview off, plain preview from now on", e)
        enabled = false
        onFailure(e)
    }

    companion object {
        private const val TAG = "LookPreview"

        /** 10-bit (HDR) preview and a renderer that failed once go straight to the view. */
        fun usesRenderer(enabled: Boolean, sdr: Boolean) = enabled && sdr
    }
}
