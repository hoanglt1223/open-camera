/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package androidx.camera.view

import android.hardware.camera2.CameraCaptureSession
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import java.util.concurrent.Executor

/**
 * CameraController builds its use cases without a Camera2 interop hook, and ImageAnalysis
 * frames lose their CaptureResult. This swaps the controller's (package-private) analysis use
 * case for an equivalent one carrying a session capture callback, so per-frame exposure is
 * known. Lives in the controller's package for that field access; on any failure the controller
 * keeps its own use case and callers fall back to estimates.
 *
 * The controller silently rebuilds its analysis use case (dropping the callback) when the
 * provider finishes initialising and when the PreviewView attaches a new viewport, both of which
 * can happen after the first bind; [ensureAttached] re-applies the swap and restarts the camera.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
object CameraControllerInterop {
    private const val TAG = "CameraControllerInterop"

    private class Spec(
        val resolutionSelector: ResolutionSelector,
        val executor: Executor,
        val analyzer: ImageAnalysis.Analyzer,
        val callback: CameraCaptureSession.CaptureCallback,
    )

    private var spec: Spec? = null
    private var attached: ImageAnalysis? = null

    /** Call after the analysis resolution and analyzer are set and before binding. */
    fun attachAnalysisCaptureCallback(
        controller: CameraController,
        resolutionSelector: ResolutionSelector,
        executor: Executor,
        analyzer: ImageAnalysis.Analyzer,
        callback: CameraCaptureSession.CaptureCallback,
    ): Boolean {
        spec = Spec(resolutionSelector, executor, analyzer, callback)
        return swap(controller)
    }

    /** Forgets the callback, e.g. when the analysis stream is no longer full-resolution. */
    fun detach() {
        spec = null
        attached = null
    }

    /**
     * Re-applies the swap when the controller rebuilt its analysis use case since the last
     * attach, then restarts the camera so the session carries the callback. Main thread only.
     * Returns true when a restart was needed.
     */
    fun ensureAttached(controller: CameraController): Boolean = try {
        val current = controller.mImageAnalysis
        if (spec == null || current == null || current === attached) {
            false
        } else {
            Log.i(TAG, "Analysis use case was rebuilt by the controller, re-attaching the capture callback")
            swap(controller) && run {
                controller.startCameraAndTrackStates()
                true
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Cannot re-attach the capture callback", e)
        false
    }

    /** Size of the bound still stream; null before the session is configured or on failure. */
    fun imageCaptureResolution(controller: CameraController): android.util.Size? = try {
        controller.mImageCapture.resolutionInfo?.resolution
    } catch (e: Throwable) {
        Log.w(TAG, "Cannot read the still stream size", e)
        null
    }

    private fun swap(controller: CameraController): Boolean = try {
        val s = checkNotNull(spec)
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(s.resolutionSelector)
            .setBackpressureStrategy(controller.imageAnalysisBackpressureStrategy)
            .setImageQueueDepth(controller.imageAnalysisImageQueueDepth)
        Camera2Interop.Extender(builder).setSessionCaptureCallback(s.callback)
        val analysis = builder.build()
        analysis.setAnalyzer(s.executor, s.analyzer)
        controller.mImageAnalysis?.clearAnalyzer()
        controller.mImageAnalysis = analysis
        attached = analysis
        true
    } catch (e: Throwable) {
        Log.w(TAG, "Cannot attach a capture callback to the analysis stream", e)
        false
    }
}
