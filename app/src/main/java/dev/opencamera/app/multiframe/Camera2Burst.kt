/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import dev.opencamera.capture.FrameExposure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One manual-exposure burst through a short Camera2 session of its own: the camera is opened
 * after CameraX released it, a single still output is configured, all requests go out in one
 * [CameraCaptureSession.captureBurst] and the device is closed again. No repeating request runs,
 * so a long still never queues behind long preview frames.
 */
class Camera2Burst(private val manager: CameraManager) {
    /** White balance, color matrix and focus copied from the preview, so the frames match it. */
    class Look(
        val gains: RggbChannelVector?,
        val transform: ColorSpaceTransform?,
        val focusDistance: Float?,
    )

    class Output(val format: Int, val size: Size)

    /**
     * Captures [frames] into [outputs] tried in order (RAW first, YUV as fallback). [onFrame] gets
     * each image with its result and must copy what it needs; the image is closed afterwards.
     * Throws when every output failed; cancellation closes the camera.
     */
    suspend fun run(
        cameraId: String,
        outputs: List<Output>,
        frames: List<FrameExposure>,
        look: Look,
        timeoutMs: Long,
        onFrame: suspend (index: Int, format: Int, image: Image, result: TotalCaptureResult) -> Unit,
    ): Int {
        val thread = HandlerThread("Camera2Burst").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        val closed = CompletableDeferred<Unit>()
        try {
            awaitAvailable(cameraId, handler)
            val dev = open(cameraId, handler, closed).also { device = it }
            var last: Throwable? = null
            for (output in outputs) {
                try {
                    val got = withTimeoutOrNull(timeoutMs) {
                        capture(dev, output, frames, look, handler, onFrame)
                    } ?: throw IllegalStateException("Burst not done within $timeoutMs ms")
                    return output.format.also { Log.i(LOG_TAG, "Burst of $got frames, format $it") }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(LOG_TAG, "Burst with format ${output.format} ${output.size} failed", e)
                    last = e
                }
            }
            throw IllegalStateException("No burst output worked", last)
        } finally {
            device?.let {
                runCatching { it.close() }
                // CameraX reopens the camera right after; wait (bounded) until it is really closed.
                withContext(NonCancellable) { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { closed.await() } }
            }
            thread.quitSafely()
        }
    }

    private suspend fun capture(
        device: CameraDevice,
        output: Output,
        frames: List<FrameExposure>,
        look: Look,
        handler: Handler,
        onFrame: suspend (Int, Int, Image, TotalCaptureResult) -> Unit,
    ): Int {
        val reader = ImageReader.newInstance(
            output.size.width, output.size.height, output.format, frames.size.coerceIn(2, MAX_BUFFERS),
        )
        val images = Channel<Image>(Channel.UNLIMITED)
        val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        val failure = CompletableDeferred<Throwable>()
        var session: CameraCaptureSession? = null
        try {
            reader.setOnImageAvailableListener({ r ->
                runCatching { r.acquireNextImage() }.getOrNull()?.let { img ->
                    if (images.trySend(img).isFailure) img.close()
                }
            }, handler)
            val s = createSession(device, reader, handler).also { session = it }
            val requests = frames.map { f -> request(device, reader, output.format, f, look) }
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = result }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    captureFailure: CaptureFailure,
                ) {
                    failure.complete(IllegalStateException("Capture failed, reason ${captureFailure.reason}"))
                }
            }, handler)
            for (i in frames.indices) {
                if (failure.isCompleted) throw failure.getCompleted()
                val image = images.receive()
                try {
                    val result = awaitResult(results, image.timestamp, failure)
                    onFrame(i, output.format, image, result)
                } finally {
                    image.close()
                }
            }
            return frames.size
        } finally {
            session?.let { runCatching { it.abortCaptures() }; runCatching { it.close() } }
            images.close()
            while (true) images.tryReceive().getOrNull()?.close() ?: break
            runCatching { reader.close() }
        }
    }

    private suspend fun awaitResult(
        results: Map<Long, TotalCaptureResult>,
        timestamp: Long,
        failure: CompletableDeferred<Throwable>,
    ): TotalCaptureResult {
        repeat(RESULT_WAIT_STEPS) {
            results[timestamp]?.let { return it }
            if (failure.isCompleted) throw failure.getCompleted()
            kotlinx.coroutines.delay(10)
        }
        throw IllegalStateException("No result for frame $timestamp")
    }

    private fun request(
        device: CameraDevice,
        reader: ImageReader,
        format: Int,
        f: FrameExposure,
        look: Look,
    ): CaptureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
        addTarget(reader.surface)
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        set(CaptureRequest.SENSOR_EXPOSURE_TIME, f.exposureTimeNs)
        set(CaptureRequest.SENSOR_SENSITIVITY, f.iso)
        set(CaptureRequest.SENSOR_FRAME_DURATION, f.frameDurationNs)
        if (look.gains != null && look.transform != null) {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, look.gains)
            set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, look.transform)
        }
        look.focusDistance?.let {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        if (format == ImageFormat.RAW_SENSOR) {
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        }
    }.build()

    /** CameraX closes the device asynchronously after unbinding; opening early would evict it mid-close. */
    private suspend fun awaitAvailable(cameraId: String, handler: Handler) {
        val available = CompletableDeferred<Unit>()
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) {
                if (id == cameraId) available.complete(Unit)
            }
        }
        manager.registerAvailabilityCallback(callback, handler)
        try {
            if (withTimeoutOrNull(AVAILABLE_TIMEOUT_MS) { available.await() } == null) {
                Log.w(LOG_TAG, "Camera $cameraId not reported available, opening anyway")
            }
        } finally {
            manager.unregisterAvailabilityCallback(callback)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(cameraId: String, handler: Handler, closed: CompletableDeferred<Unit>): CameraDevice =
        withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resume(camera) else camera.close()
                    }

                    override fun onClosed(camera: CameraDevice) {
                        closed.complete(Unit)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                    }
                }, handler)
            }
        } ?: throw IllegalStateException("Camera $cameraId did not open within $OPEN_TIMEOUT_MS ms")

    @Suppress("DEPRECATION")
    private suspend fun createSession(device: CameraDevice, reader: ImageReader, handler: Handler): CameraCaptureSession =
        withTimeoutOrNull(SESSION_TIMEOUT_MS) {
            suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Session not configured"))
                    }
                }, handler)
            }
        } ?: throw IllegalStateException("Session not configured within $SESSION_TIMEOUT_MS ms")

    companion object {
        private const val LOG_TAG = "Camera2Burst"
        private const val MAX_BUFFERS = 4
        private const val AVAILABLE_TIMEOUT_MS = 1500L
        private const val OPEN_TIMEOUT_MS = 2500L
        private const val SESSION_TIMEOUT_MS = 2500L
        private const val CLOSE_TIMEOUT_MS = 1000L
        private const val RESULT_WAIT_STEPS = 200

        /** Largest output of [format], or null when the camera has none. */
        fun largest(chars: CameraCharacteristics, format: Int): Size? =
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height }

        fun minFrameDurationNs(chars: CameraCharacteristics, format: Int, size: Size): Long =
            runCatching {
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputMinFrameDuration(format, size)
            }.getOrNull() ?: 0L

        /** Manual sensor control is needed for per-frame exposure. */
        fun supportsManual(chars: CameraCharacteristics): Boolean =
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    }
}
