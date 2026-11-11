/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A constrained high-speed configuration the HAL advertises. */
data class HighSpeedConfig(val size: Size, val fps: Int)

/**
 * Slow motion straight on Camera2 when CameraX reports no high-speed qualities (common when the
 * ROM has no high-speed camcorder profiles): a [CameraConstrainedHighSpeedCaptureSession] feeds
 * a preview surface and a [MediaRecorder] at the same size. The file plays in real time at
 * [HighSpeedConfig.fps]; [dev.opencamera.pipeline.video.VideoRetimer] stretches it afterwards.
 */
class HighSpeedSession(private val context: Context, private val cameraId: String, val config: HighSpeedConfig) {
    private val thread = HandlerThread("HighSpeed").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var output: File? = null
    var recording = false
        private set

    companion object {
        private const val TAG = "HighSpeedSession"

        /** High-speed configs of [cameraId], best first (prefers 1080p120, then the highest rate). */
        fun configs(context: Context, cameraId: String): List<HighSpeedConfig> = try {
            val ch = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            val capable = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (!capable || map == null) {
                emptyList()
            } else {
                map.highSpeedVideoSizes.flatMap { size ->
                    map.getHighSpeedVideoFpsRangesFor(size).filter { it.lower == it.upper && it.upper >= 120 }
                        .map { HighSpeedConfig(size, it.upper) }
                }.distinct().sortedWith(
                    compareByDescending<HighSpeedConfig> { it.size.width >= 1920 && it.fps == 120 }
                        .thenByDescending { it.fps }
                        .thenByDescending { it.size.width * it.size.height },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "high speed probe failed", e)
            emptyList()
        }

        private fun sensorOrientation(context: Context, cameraId: String) = runCatching {
            context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 90
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(file: File, rotationDeg: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(file.path)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(config.size.width, config.size.height)
        r.setVideoFrameRate(config.fps)
        r.setVideoEncodingBitRate(config.size.width * config.size.height * config.fps / 5)
        r.setOrientationHint((sensorOrientation(context, cameraId) - rotationDeg + 360) % 360)
        r.prepare()
        return r
    }

    /** Opens the camera with a preview only; [preview] must be sized to [HighSpeedConfig.size]. */
    suspend fun open(preview: Surface) {
        previewSurface = preview
        device = openDevice()
        configure(listOf(preview))
    }

    /** Starts recording into [file]; the device rotation in degrees sets the orientation hint. */
    suspend fun start(file: File, rotationDeg: Int) {
        val preview = previewSurface ?: error("not open")
        val r = newRecorder(file, rotationDeg)
        recorder = r
        output = file
        configure(listOf(preview, r.surface))
        r.start()
        recording = true
    }

    /** Stops recording, returns the real-time high-speed file, and goes back to preview. */
    suspend fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        recording = false
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        previewSurface?.let { runCatching { configure(listOf(it)) } }
        return output.takeIf { ok }
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { recorder?.release() }
        runCatching { device?.close() }
        session = null
        recorder = null
        device = null
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(): CameraDevice {
        val manager = context.getSystemService(CameraManager::class.java)
        var lastError: Exception? = null
        // CameraX may still be releasing the camera right after unbindAll().
        repeat(5) { attempt ->
            try {
                return suspendCancellableCoroutine { cont ->
                    manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) = cont.resume(camera)
                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("disconnected"))
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error"))
                        }
                    }, handler)
                }
            } catch (e: Exception) {
                lastError = e
                delay(200L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("camera open failed")
    }

    private suspend fun configure(targets: List<Surface>) {
        val cam = device ?: error("no camera")
        runCatching { session?.close() }
        session = null
        @Suppress("DEPRECATION")
        val s = suspendCancellableCoroutine { cont ->
            cam.createConstrainedHighSpeedCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) =
                    cont.resume(session as CameraConstrainedHighSpeedCaptureSession)

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("high speed configure failed"))
                }
            }, handler)
        }
        session = s
        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            targets.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        }.build()
        s.setRepeatingBurst(s.createHighSpeedRequestList(request), null, handler)
    }
}
