/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.opencamera.app.R
import dev.opencamera.app.multiframe.MultiFrameCaptureController
import dev.opencamera.app.multiframe.MultiFrameSaver
import dev.opencamera.app.multiframe.ProcessingQueue
import dev.opencamera.capture.BurstKind
import dev.opencamera.capture.BurstPlan
import dev.opencamera.pipeline.Burst
import dev.opencamera.pipeline.MergedImage
import dev.opencamera.pipeline.MultiFrameMode
import dev.opencamera.pipeline.MultiFrameProcessor
import dev.opencamera.pipeline.StackOp
import dev.opencamera.pipeline.StackParams
import dev.opencamera.pipeline.creative.LumaFrame
import dev.opencamera.pipeline.creative.MoonDetector
import dev.opencamera.pipeline.creative.MotionSubjectLocator
import dev.opencamera.pipeline.creative.SportsPicker
import dev.opencamera.pipeline.creative.SubjectLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Creative capture modes on top of the burst stream. */
enum class CreativeMode(@StringRes val label: Int) {
    OFF(R.string.creative_off),
    MOON(R.string.creative_moon),
    LONG_EXPOSURE(R.string.creative_long_exposure),
    LIGHT_TRAILS(R.string.creative_light_trails),
    REMOVE_PEOPLE(R.string.creative_remove_people),
    FOCUS_STACK(R.string.creative_focus_stack),
    SPORTS(R.string.creative_sports),
}

/** Frames, spacing and stack operation of a creative mode. */
data class CreativePlan(
    val mode: CreativeMode,
    val frames: Int,
    val op: StackOp?,
    val stride: Int = 1,
    val evCompensation: Float = 0f,
    val align: Boolean = true,
    val delayMs: Long = 0,
) {
    companion object {
        /** Pure planning, unit tested. [onTripod] turns alignment off so motion blur survives. */
        fun of(mode: CreativeMode, onTripod: Boolean): CreativePlan? = when (mode) {
            CreativeMode.OFF -> null
            CreativeMode.MOON -> CreativePlan(mode, 20, StackOp.MOON, evCompensation = -1f, delayMs = 2000)
            CreativeMode.LONG_EXPOSURE -> CreativePlan(mode, 30, StackOp.MEAN, align = !onTripod)
            CreativeMode.LIGHT_TRAILS -> CreativePlan(mode, 45, StackOp.MAX, align = !onTripod)
            CreativeMode.REMOVE_PEOPLE -> CreativePlan(mode, 9, StackOp.MEDIAN, stride = 10)
            CreativeMode.FOCUS_STACK -> CreativePlan(mode, 6, StackOp.FOCUS)
            CreativeMode.SPORTS -> CreativePlan(mode, 20, null)
        }

        /** Focus distances (diopters) for a sweep from [minFocusDiopters] (nearest) to infinity. */
        fun focusSweep(minFocusDiopters: Float, steps: Int): FloatArray {
            if (steps < 2 || minFocusDiopters <= 0f) return FloatArray(0)
            // Even steps in diopters = even depth-of-field steps.
            return FloatArray(steps) { minFocusDiopters * (steps - 1 - it) / (steps - 1) }
        }
    }
}

/**
 * Captures and processes creative bursts (moon, long exposure, light trails, passer-by
 * removal, focus stacking, sports burst). Every path falls back to a single frame or to the
 * regular photo; nothing here may crash the camera.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CreativeCaptureController(
    private val context: Context,
    private val multiFrame: MultiFrameCaptureController,
    private val queue: ProcessingQueue,
) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val _mode = MutableStateFlow(
        runCatching { CreativeMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(CreativeMode.OFF)
    )
    val mode = _mode.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** String resources to show as a toast. */
    val messages = _messages.asSharedFlow()

    /** Set when a moon-like target is seen while zoomed in and Moon mode is off. */
    private val _moonSeen = MutableStateFlow(false)
    val moonSeen = _moonSeen.asStateFlow()

    @Volatile
    private var lastMoon: MoonDetector.Detection? = null

    @Volatile
    var zoomRatio = 1f

    /** Set when Moon mode switched to a telephoto camera; otherwise Moon uses crop + Super Res. */
    @Volatile
    var moonOnTele = false

    /** Normalised point the user selected (tap to focus) for sports subject tracking. */
    @Volatile
    var subjectHint: Pair<Float, Float>? = null

    /** Finds the sports subject; a segmentation model can replace the motion-based default. */
    @Volatile
    var subjectLocator: SubjectLocator = MotionSubjectLocator

    private var frameCounter = 0
    private var moonHits = 0

    fun setMode(value: CreativeMode) {
        _mode.value = value
        prefs.edit { putString(KEY_MODE, value.name) }
    }

    fun cycleMode() = setMode(CreativeMode.entries[(_mode.value.ordinal + 1) % CreativeMode.entries.size])

    /** Idle analysis frames: moon detection a few times per second. */
    fun tap(image: ImageProxy) {
        if (frameCounter++ % 10 != 0) return
        lastRotation = image.imageInfo.rotationDegrees
        val moonMode = _mode.value == CreativeMode.MOON
        if (!moonMode && zoomRatio < 2f) {
            moonHits = 0
            return
        }
        val plane = image.planes[0]
        val step = 4
        val w = image.width / step
        val h = image.height / step
        val buf = plane.buffer
        val small = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * step * plane.rowStride
            for (x in 0 until w) small[y * w + x] = buf.get(row + x * step * plane.pixelStride)
        }
        val d = MoonDetector.detect(small, w, h)
        lastMoon = d
        moonHits = if (d != null) moonHits + 1 else 0
        if (!moonMode && moonHits >= 3) _moonSeen.value = true
    }

    fun clearMoonSuggestion() {
        _moonSeen.value = false
        moonHits = 0
    }

    /**
     * Runs the active creative mode. Returns false when the regular capture should run instead
     * (mode off, no analysis stream); [onSaved] gets the saved URIs on the main thread.
     */
    suspend fun capture(
        cameraControl: CameraControl,
        cameraInfo: CameraInfo,
        location: Location?,
        onSaved: (List<Uri>) -> Unit,
    ): Boolean {
        val motion = multiFrame.motionMeter.state()
        val plan = CreativePlan.of(_mode.value, motion.onTripod) ?: return false
        val size = multiFrame.analyzer.frameSize
        if (size == null) {
            _messages.tryEmit(R.string.creative_unavailable)
            return false
        }
        val frames = multiFrame.framesThatFit(size.width, size.height, plan.frames)
        if (frames < 2) {
            _messages.tryEmit(R.string.creative_unavailable)
            return false
        }
        if (plan.mode == CreativeMode.MOON) {
            if (!motion.onTripod && motion.angularRms > SHAKE_RAD_S) _messages.tryEmit(R.string.creative_moon_shake)
            meterOnMoon(cameraControl)
            delay(plan.delayMs)
        }
        val timestamp = System.currentTimeMillis()
        // Moon without a telephoto lens: unzoomed full-resolution frames, cropped around the
        // moon and super-resolved after alignment.
        val moonSuperRes = plan.mode == CreativeMode.MOON && !moonOnTele && multiFrame.fullRes
        val moonZoom = maxOf(zoomRatio, MOON_MIN_ZOOM)
        val moonCenter = lastMoon?.let { 0.5f + (it.cx - 0.5f) / zoomRatio to 0.5f + (it.cy - 0.5f) / zoomRatio }
        val captured: Pair<Burst, MultiFrameCaptureController.CapturedBurst?> = try {
            if (plan.mode == CreativeMode.FOCUS_STACK) {
                focusSweep(cameraControl, cameraInfo, size.width, size.height, frames) to null
            } else {
                val burstPlan = BurstPlan(BurstKind.SINGLE, frames, evCompensation = plan.evCompensation)
                val c = try {
                    multiFrame.capture(cameraControl, cameraInfo, burstPlan, plan.stride, unzoom = moonSuperRes)
                } catch (e: CameraControl.OperationCanceledException) {
                    // The session was rebound under us (e.g. the preview look effect attaching);
                    // it is back within a second, so try once more.
                    Log.w(TAG, "Camera rebound during ${plan.mode}, retrying", e)
                    delay(REBIND_RETRY_MS)
                    multiFrame.capture(cameraControl, cameraInfo, burstPlan, plan.stride, unzoom = moonSuperRes)
                }
                c.burst to c
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Includes native link/allocation errors: fall back to the regular photo.
            Log.e(TAG, "Creative capture ${plan.mode} failed", e)
            _messages.tryEmit(R.string.creative_failed)
            return false
        } finally {
            if (plan.mode == CreativeMode.MOON) runCatching { cameraControl.cancelFocusAndMetering() }
        }
        val (burst, collected) = captured
        val moonCrop = if (moonSuperRes) {
            MultiFrameProcessor.zoomCrop(
                burst.width, burst.height, moonZoom, moonCenter?.first ?: 0.5f, moonCenter?.second ?: 0.5f,
            )
        } else {
            null
        }
        val hint = subjectHint
        queue.submit {
            val uris = ArrayList<Uri>()
            try {
                burst.use { uris += process(it, plan, collected, location, timestamp, moonCrop, hint) }
            } catch (e: Throwable) {
                Log.e(TAG, "Creative processing failed", e)
            }
            withContext(Dispatchers.Main) { onSaved(uris) }
        }
        return true
    }

    private fun process(
        burst: Burst,
        plan: CreativePlan,
        collected: MultiFrameCaptureController.CapturedBurst?,
        location: Location?,
        timestamp: Long,
        moonCrop: IntArray?,
        hint: Pair<Float, Float>?,
    ): List<Uri> {
        val t0 = SystemClock.elapsedRealtime()
        val images: List<MergedImage> = if (plan.op == null) {
            // Sports: keep the frames where the tracked subject is sharpest.
            val picks = pickSports(burst, hint)
            picks.toList().mapNotNull { burst.exportFrame(it) }
        } else if (moonCrop != null) {
            listOfNotNull(
                runCatching { burst.process(MultiFrameMode.SUPER_RES, 0f, crop = moonCrop) }
                    .onFailure { Log.e(TAG, "Moon super-res failed, stacking instead", it) }.getOrNull()
                    ?: MultiFrameProcessor.stackOrFallback(burst, StackOp.MOON, StackParams(align = plan.align))
            )
        } else {
            listOfNotNull(
                MultiFrameProcessor.stackOrFallback(burst, plan.op, StackParams(align = plan.align))
            )
        }
        val rotation = collected?.collected?.rotationDegrees ?: lastRotation
        val result = collected?.collected?.captureResult
        return images.mapIndexedNotNull { i, image ->
            runCatching {
                MultiFrameSaver.save(
                    context, image.toJpeg(), image.width, image.height,
                    MultiFrameSaver.Metadata(
                        timestamp = timestamp + i,
                        rotationDegrees = rotation,
                        captureResult = result,
                        location = location,
                        description = "${plan.mode.name} ${image.frameCount} of ${burst.frameCount} frames",
                    ),
                )
            }.onFailure { Log.e(TAG, "Saving creative result failed", it) }.getOrNull()
        }.also {
            Log.i(TAG, "${plan.mode}: ${burst.frameCount} frames ${burst.width}x${burst.height}, " +
                "${it.size} saved in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    @Volatile
    private var lastRotation = 0

    /** Indices of the frames to keep; falls back to whole-frame sharpness, then to frame 0. */
    private fun pickSports(burst: Burst, hint: Pair<Float, Float>?): IntArray {
        val picks = runCatching {
            val factor = maxOf(1, burst.width / SPORTS_ANALYSIS_WIDTH)
            val frames = (0 until burst.frameCount).map { i ->
                LumaFrame(burst.width / factor, burst.height / factor, checkNotNull(burst.exportLuma(i, factor)))
            }
            SportsPicker.pick(frames, SPORTS_KEEP, hint, subjectLocator)
        }.onFailure { Log.w(TAG, "Subject tracking failed, ranking whole frames", it) }.getOrNull()
            ?: runCatching { burst.rankBySharpness().take(SPORTS_KEEP).toIntArray() }.getOrNull()
        return picks?.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
    }

    /** Spot AE/AF on the detected disc so the bright limb does not clip. */
    private suspend fun meterOnMoon(cameraControl: CameraControl) {
        val d = lastMoon
        if (d == null) {
            _messages.tryEmit(R.string.creative_moon_not_found)
            return
        }
        runCatching {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(d.cx, d.cy, (d.radius * 2).coerceIn(0.02f, 0.3f))
            withTimeout(3000) {
                cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AF)
                        .disableAutoCancel()
                        .build()
                ).await()
            }
        }.onFailure { Log.w(TAG, "Moon metering failed, using frame metering", it) }
    }

    /** Steps manual focus from near to infinity, one stream frame per step. */
    private suspend fun focusSweep(
        cameraControl: CameraControl,
        cameraInfo: CameraInfo,
        width: Int,
        height: Int,
        frames: Int,
    ): Burst {
        val info = Camera2CameraInfo.from(cameraInfo)
        val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val steps = CreativePlan.focusSweep(minFocus, frames)
        if (steps.isEmpty()) {
            _messages.tryEmit(R.string.creative_focus_unsupported)
            error("Fixed-focus camera")
        }
        val c2 = Camera2CameraControl.from(cameraControl)
        val saved = c2.getCaptureRequestOptions()
        val burst = Burst(width, height, steps.size)
        try {
            for ((i, diopters) in steps.withIndex()) {
                c2.addCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                        .build()
                ).await()
                delay(FOCUS_SETTLE_MS)
                withTimeout(3000) {
                    multiFrame.analyzer.arm(burst, i + 1, 0, { true }).await()
                }.also { lastRotation = it.rotationDegrees }
            }
            return burst
        } catch (e: Throwable) {
            multiFrame.analyzer.disarm()
            burst.close()
            throw e
        } finally {
            runCatching { c2.setCaptureRequestOptions(saved) }
        }
    }

    private companion object {
        const val TAG = "CreativeCapture"
        const val KEY_MODE = "creative_mode"
        const val SHAKE_RAD_S = 0.05f
        const val SPORTS_KEEP = 3
        const val FOCUS_SETTLE_MS = 350L
        const val REBIND_RETRY_MS = 1000L
        const val MOON_MIN_ZOOM = 2f
        const val SPORTS_ANALYSIS_WIDTH = 400
    }
}
