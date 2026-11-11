/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.opencamera.capture.BurstKind
import dev.opencamera.capture.BurstPlan
import dev.opencamera.capture.ExposureCaps
import dev.opencamera.capture.ExposurePlanner
import dev.opencamera.capture.MotionMeter
import dev.opencamera.capture.MultiFrameSetting
import dev.opencamera.pipeline.Burst
import dev.opencamera.pipeline.MultiFrameMode
import dev.opencamera.pipeline.MultiFrameProcessor
import dev.opencamera.vendorbridge.ProcessingSource
import dev.opencamera.vendorbridge.SourcePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Burst capture over the CameraX ImageAnalysis stream (full-resolution YUV) with exposure set
 * through Camera2 interop, followed by native merging on a background queue.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MultiFrameCaptureController(
    private val context: Context,
    private val queue: ProcessingQueue,
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val _setting = MutableStateFlow(
        runCatching {
            MultiFrameSetting.valueOf(prefs.getString(KEY_SETTING, null) ?: "")
        }.getOrDefault(MultiFrameSetting.HDR_AUTO)
    )
    val setting: StateFlow<MultiFrameSetting> = _setting.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)

    /** Merge progress 0..1 of the job being processed, or null when idle. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    val analyzer = BurstAnalyzer()
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val motionMeter = MotionMeter(context)

    val enabled: Boolean
        get() = _setting.value != MultiFrameSetting.OFF

    /** Set while Pro mode holds a manual ISO/shutter, which a burst plan would override. */
    @Volatile
    var manualExposureActive = false

    fun setSetting(value: MultiFrameSetting) {
        _setting.value = value
        prefs.edit { putString(KEY_SETTING, value.name) }
    }

    fun cycleSetting() = setSetting(
        MultiFrameSetting.entries[(_setting.value.ordinal + 1) % MultiFrameSetting.entries.size]
    )

    fun astroSuggested(): Boolean {
        val meter = analyzer.lastMeter ?: return false
        return ExposurePlanner.suggestAstro(meter, motionMeter.state())
    }

    fun plan(cameraInfo: CameraInfo, zoomRatio: Float): BurstPlan? {
        if (!enabled || manualExposureActive) return null
        val meter = analyzer.lastMeter ?: return null
        val size = analyzer.frameSize ?: return null
        // Some HALs cannot stream full-resolution YUV next to JPEG; a merged low-resolution
        // frame would look worse than a single full-resolution capture.
        if (size.width.toLong() * size.height < MIN_BURST_PIXELS) {
            Log.w(LOG_TAG, "Analysis stream is only ${size.width}x${size.height}, taking a single frame")
            return null
        }
        if (queue.pending.value >= MAX_PENDING_JOBS) {
            Log.w(LOG_TAG, "Processing queue busy, taking a single frame")
            return null
        }
        val plan = ExposurePlanner.plan(
            _setting.value, meter, motionMeter.state(), exposureCaps(cameraInfo), zoomRatio,
            focalLengthPx = size.width * 0.6f,
        )
        if (plan.kind == BurstKind.SINGLE) return null
        val frames = framesThatFit(size.width, size.height, plan.frameCount)
        if (frames < 2) return null
        return plan.copy(frameCount = frames)
    }

    class CapturedBurst(
        val burst: Burst,
        val plan: BurstPlan,
        val collected: BurstAnalyzer.Collected,
        val captureMs: Long,
    )

    /** Collects the burst frames. Restores exposure settings before returning. */
    suspend fun capture(
        cameraControl: CameraControl,
        cameraInfo: CameraInfo,
        plan: BurstPlan,
    ): CapturedBurst {
        val size = checkNotNull(analyzer.frameSize)
        val burst = Burst(size.width, size.height, plan.frameCount)
        val start = SystemClock.elapsedRealtime()
        val c2 = Camera2CameraControl.from(cameraControl)
        val savedOptions = c2.getCaptureRequestOptions()
        val savedEvIndex = cameraInfo.exposureState.exposureCompensationIndex
        try {
            val manualT = plan.exposureTimeNs
            val manualIso = plan.iso
            val ready: (CaptureResult?) -> Boolean
            val maxSkip: Int
            if (manualT != null && manualIso != null) {
                c2.addCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF
                        )
                        .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, manualT)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, manualT)
                        .build()
                ).await()
                ready = { r ->
                    val t = r?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    t != null && abs(t - manualT) <= manualT / 10
                }
                maxSkip = if (manualT > 500_000_000L) 2 else 6
            } else {
                val state = cameraInfo.exposureState
                val step = state.exposureCompensationStep.toFloat()
                val index = if (state.isExposureCompensationSupported && step > 0f) {
                    (savedEvIndex + (plan.evCompensation / step).roundToInt())
                        .coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
                } else {
                    savedEvIndex
                }
                if (index != savedEvIndex) cameraControl.setExposureCompensationIndex(index).await()
                ready = { r ->
                    r?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) == index &&
                            r.get(CaptureResult.CONTROL_AE_STATE) != CaptureResult.CONTROL_AE_STATE_SEARCHING
                }
                maxSkip = if (index != savedEvIndex) 12 else 0
            }
            val collected = withTimeout(plan.estimatedCaptureMs * 3 + 4000L) {
                analyzer.arm(burst, plan.frameCount, maxSkip, ready).await()
            }
            return CapturedBurst(burst, plan, collected, SystemClock.elapsedRealtime() - start)
        } catch (e: Throwable) {
            analyzer.disarm()
            burst.close()
            throw e
        } finally {
            runCatching { c2.setCaptureRequestOptions(savedOptions) }
            if (cameraInfo.exposureState.exposureCompensationIndex != savedEvIndex) {
                runCatching { cameraControl.setExposureCompensationIndex(savedEvIndex) }
            }
        }
    }

    /** Queues merging and saving; [onSaved] runs on the main thread. */
    fun process(captured: CapturedBurst, location: Location?, onSaved: (Uri?) -> Unit) {
        val timestamp = System.currentTimeMillis()
        queue.submit {
            var uri: Uri? = null
            try {
                captured.burst.use { burst ->
                    _progress.value = 0f
                    val t0 = SystemClock.elapsedRealtime()
                    val mode = when (captured.plan.kind) {
                        BurstKind.NIGHT -> MultiFrameMode.NIGHT
                        BurstKind.ASTRO -> MultiFrameMode.ASTRO
                        BurstKind.SUPER_RES -> MultiFrameMode.SUPER_RES
                        else -> MultiFrameMode.HDR
                    }
                    val image = MultiFrameProcessor.processOrFallback(
                        burst, mode, captured.plan.toneEvStep
                    ) { _progress.value = it * 0.85f } ?: return@use
                    val t1 = SystemClock.elapsedRealtime()
                    val jpeg = image.toJpeg()
                    _progress.value = 0.95f
                    val t2 = SystemClock.elapsedRealtime()
                    uri = MultiFrameSaver.save(
                        context, jpeg, image.width, image.height,
                        MultiFrameSaver.Metadata(
                            timestamp = timestamp,
                            rotationDegrees = captured.collected.rotationDegrees,
                            captureResult = captured.collected.captureResult,
                            location = location,
                            description = SourcePolicy.label(ProcessingSource.OPEN, "${mode.name} ${image.frameCount} frames"),
                        )
                    )
                    val t3 = SystemClock.elapsedRealtime()
                    Log.i(
                        LOG_TAG,
                        "kind=${captured.plan.kind} frames=${image.frameCount} " +
                                "size=${image.width}x${image.height} " +
                                "t=${captured.plan.exposureTimeNs} iso=${captured.plan.iso} " +
                                "ev=${captured.plan.evCompensation} capture=${captured.captureMs}ms " +
                                "align=${image.msAlign} merge=${image.msMerge} finish=${image.msFinish} " +
                                "process=${t1 - t0}ms jpeg=${t2 - t1}ms save=${t3 - t2}ms " +
                                "noise=${image.noiseSigma} weight=${image.meanWeight} ref=${image.referenceIndex}"
                    )
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Multi-frame processing failed", e)
            } finally {
                _progress.value = null
            }
            withContext(Dispatchers.Main) { onSaved(uri) }
        }
    }

    fun release() {
        analyzer.disarm()
        motionMeter.stop()
        analysisExecutor.shutdown()
    }

    private fun exposureCaps(cameraInfo: CameraInfo): ExposureCaps {
        val info = Camera2CameraInfo.from(cameraInfo)
        val t = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val s = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val maxAnalog = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
        return ExposureCaps(
            minExposureNs = t?.lower ?: 100_000L,
            maxExposureNs = t?.upper ?: 250_000_000L,
            minIso = s?.lower ?: 100,
            // Prefer analog gain; digital gain beyond it only adds noise the merge must remove.
            maxIso = maxOf(maxAnalog ?: 0, minOf(s?.upper ?: 3200, 6400)),
        )
    }

    private fun framesThatFit(width: Int, height: Int, wanted: Int): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return wanted
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pixels = width.toLong() * height
        // Each frame is 1.5 bytes/pixel; merging needs ~14 bytes/pixel of float buffers.
        val budget = (info.availMem - info.threshold) / 2 - pixels * 14
        val fit = (budget / (pixels * 3 / 2)).toInt()
        if (fit < wanted) Log.w(LOG_TAG, "Memory allows $fit of $wanted frames")
        return minOf(wanted, fit)
    }

    companion object {
        private const val LOG_TAG = "MultiFrame"
        private const val KEY_SETTING = "multi_frame_setting"
        private const val MAX_PENDING_JOBS = 2
        private const val MIN_BURST_PIXELS = 6_000_000L
    }
}
