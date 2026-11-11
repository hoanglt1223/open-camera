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
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
object CameraControllerInterop {
    private const val TAG = "CameraControllerInterop"

    /** Call after the analysis resolution and analyzer are set and before binding. */
    fun attachAnalysisCaptureCallback(
        controller: CameraController,
        resolutionSelector: ResolutionSelector,
        executor: Executor,
        analyzer: ImageAnalysis.Analyzer,
        callback: CameraCaptureSession.CaptureCallback,
    ): Boolean = try {
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(controller.imageAnalysisBackpressureStrategy)
            .setImageQueueDepth(controller.imageAnalysisImageQueueDepth)
        Camera2Interop.Extender(builder).setSessionCaptureCallback(callback)
        val analysis = builder.build()
        analysis.setAnalyzer(executor, analyzer)
        controller.mImageAnalysis?.clearAnalyzer()
        controller.mImageAnalysis = analysis
        true
    } catch (e: Throwable) {
        Log.w(TAG, "Cannot attach a capture callback to the analysis stream", e)
        false
    }
}
