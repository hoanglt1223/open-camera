/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageProxy
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.opencamera.capture.BurstKind
import dev.opencamera.capture.BurstPlan
import dev.opencamera.capture.BurstSequence
import dev.opencamera.capture.BurstSource
import dev.opencamera.capture.ExposureCaps
import dev.opencamera.capture.ExposurePlanner
import dev.opencamera.capture.MotionMeter
import dev.opencamera.capture.MultiFrameSetting
import dev.opencamera.capture.StreamConfig
import dev.opencamera.capture.StreamPolicy
import dev.opencamera.pipeline.Burst
import dev.opencamera.pipeline.MultiFrameProcessor
import dev.opencamera.pipeline.RawDevelopParams
import dev.opencamera.pipeline.queue.BurstArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Burst capture with exposure set through Camera2 interop, followed by native merging on a
 * background queue. Frames come from the CameraX ImageAnalysis stream (YUV) or, in the RAW
 * session, from RAW_SENSOR still captures developed by the pipeline.
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
    private val captureProgress = MutableStateFlow<Float?>(null)

    /** Progress 0..1 of a running Camera2 burst, else of the merge being processed, or null when idle. */
    val progress: Flow<Float?> = combine(captureProgress, _progress) { capture, merge -> capture ?: merge }

    /**
     * Lets a Camera2 burst take the camera from CameraX: [pause] freezes the viewfinder and
     * unbinds, [resume] binds again. Main thread. Null while no activity is attached.
     */
    interface BurstHost {
        fun pause()
        fun resume()
    }

    @Volatile
    var burstHost: BurstHost? = null

    private val _cancellable = MutableStateFlow(false)

    /** True while a Camera2 burst runs; a shutter tap cancels it. */
    val cancellable: StateFlow<Boolean> = _cancellable.asStateFlow()

    val analyzer = BurstAnalyzer()
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val motionMeter = MotionMeter(context)

    /** Per-frame exposure for the analysis stream (attached through the controller interop). */
    val resultTracker = CaptureResultTracker().also { analyzer.resultTracker = it }

    val enabled: Boolean
        get() = _setting.value != MultiFrameSetting.OFF

    /** Set while Pro mode holds a manual ISO/shutter, which a burst plan would override. */
    @Volatile
    var manualExposureActive = false

    /** Pro manual exposure long enough to be taken as a one-frame Camera2 burst, else null. */
    @Volatile
    var manualStill: BurstPlan? = null

    /** Plan to take as a Camera2 burst for a shot planned as [plan], or null for the CameraX path. */
    fun camera2Plan(plan: BurstPlan): BurstPlan? {
        val manual = manualStill?.takeIf { plan.kind == BurstKind.SINGLE }
        val candidate = manual ?: plan.takeIf { it.kind != BurstKind.SINGLE } ?: return null
        return candidate.takeIf { streamConfig == StreamConfig.FULL_RES_RAW && burstHost != null }
    }

    /** Session layout currently bound; set by the activity after binding. */
    @Volatile
    var streamConfig = StreamConfig.STANDARD

    /** Largest RAW_SENSOR output of the bound camera, when it has one. */
    @Volatile
    var rawSize: Size? = null

    private val _configCap = MutableStateFlow(StreamConfig.FULL_RES_RAW)

    /** Highest session layout allowed after failures in this process (fallback chain). */
    val configCap: StateFlow<StreamConfig> = _configCap.asStateFlow()

    private fun readSource() = runCatching { BurstSource.valueOf(prefs.getString(KEY_SOURCE, null) ?: "") }
        .getOrDefault(BurstSource.AUTO)

    private val _sourceFlow = MutableStateFlow(readSource())

    /** Image quality (burst source); also written by the settings screen. */
    val sourceFlow: StateFlow<BurstSource> = _sourceFlow.asStateFlow()

    // Held here: SharedPreferences keeps listeners only weakly.
    private val sourceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SOURCE) _sourceFlow.value = readSource()
    }.also { prefs.registerOnSharedPreferenceChangeListener(it) }

    var source: BurstSource
        get() = _sourceFlow.value
        set(value) {
            prefs.edit { putString(KEY_SOURCE, value.name) }
            _sourceFlow.value = value
        }

    /** True when shots must come from the app path (no JPEG ImageCapture is bound). */
    val fullRes: Boolean
        get() = streamConfig != StreamConfig.STANDARD

    fun setSetting(value: MultiFrameSetting) {
        _setting.value = value
        prefs.edit { putString(KEY_SETTING, value.name) }
    }

    fun cycleSetting() = setSetting(
        MultiFrameSetting.entries[(_setting.value.ordinal + 1) % MultiFrameSetting.entries.size]
    )

    /** Limits [config] to the current fallback cap. */
    fun capped(config: StreamConfig): StreamConfig =
        if (config.ordinal > _configCap.value.ordinal) _configCap.value else config

    /** Records that [failed] did not work; later binds use the next layout of the chain. */
    fun demote(failed: StreamConfig) {
        val next = StreamPolicy.fallback(failed) ?: StreamConfig.STANDARD
        if (next.ordinal < _configCap.value.ordinal) {
            Log.w(LOG_TAG, "Stream layout $failed failed, using $next from now on")
            _configCap.value = next
        }
    }

    fun astroSuggested(): Boolean {
        val meter = analyzer.lastMeter ?: return false
        return ExposurePlanner.suggestAstro(meter, motionMeter.state())
    }

    /** Frame size of a burst in the bound session. */
    fun burstSize(): Size? =
        if (streamConfig == StreamConfig.FULL_RES_RAW) rawSize else analyzer.frameSize

    /** Scales the viewfinder by the given factor (1 = none) while Super Res captures at 1x. Main thread. */
    var previewZoomCompensation: ((Float) -> Unit)? = null

    fun plan(cameraInfo: CameraInfo, zoomRatio: Float): BurstPlan? {
        if (!enabled || manualExposureActive) return null
        val meter = analyzer.lastMeter ?: return null
        val size = burstSize() ?: return null
        // Some HALs cannot stream full-resolution YUV next to JPEG; a merged low-resolution
        // frame would look worse than a single full-resolution capture.
        if (size.width.toLong() * size.height < MIN_BURST_PIXELS) {
            Log.w(LOG_TAG, "Burst frames are only ${size.width}x${size.height}, taking a single frame")
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
        /** Zoom crop applied after merging (unzoomed or RAW frames), or null. */
        val crop: IntArray? = null,
        val source: String = "YUV",
    )

    /**
     * Collects the burst frames. Restores exposure and zoom before returning. [frameStride]
     * keeps every n-th stream frame only. With [unzoom] (Super Res) a zoomed YUV stream is
     * captured at 1x and the zoom crop is applied after the merge. [takeRaw] takes one
     * RAW_SENSOR still in the RAW session.
     */
    suspend fun capture(
        cameraControl: CameraControl,
        cameraInfo: CameraInfo,
        plan: BurstPlan,
        frameStride: Int = 1,
        unzoom: Boolean = plan.kind == BurstKind.SUPER_RES,
        takeRaw: (suspend () -> ImageProxy)? = null,
    ): CapturedBurst {
        val raw = streamConfig == StreamConfig.FULL_RES_RAW && takeRaw != null
        val zoom = cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val resetZoom = unzoom && !raw && streamConfig == StreamConfig.FULL_RES_YUV && zoom > 1.05f
        // RAW frames are never cropped by the HAL, so a zoom stop is always cropped afterwards.
        val cropZoom = if (resetZoom || (raw && zoom > 1.05f)) zoom else 1f
        val start = SystemClock.elapsedRealtime()
        val c2 = Camera2CameraControl.from(cameraControl)
        val savedOptions = c2.getCaptureRequestOptions()
        val savedEvIndex = cameraInfo.exposureState.exposureCompensationIndex
        try {
            val manualT = plan.exposureTimeNs
            val manualIso = plan.iso
            val exposureReady: (CaptureResult?) -> Boolean
            var maxSkip: Int
            val extra = CaptureRequestOptions.Builder()
            if (raw) {
                extra.setCaptureRequestOption(
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
                )
            }
            if (manualT != null && manualIso != null) {
                extra.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, manualT)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, manualT)
                exposureReady = { r ->
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
                if (index != savedEvIndex) bounded("EV") { cameraControl.setExposureCompensationIndex(index).await() }
                exposureReady = { r ->
                    r?.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) == index &&
                        r.get(CaptureResult.CONTROL_AE_STATE) != CaptureResult.CONTROL_AE_STATE_SEARCHING
                }
                maxSkip = if (index != savedEvIndex) 12 else 0
            }
            bounded("burst options") { c2.addCaptureRequestOptions(extra.build()).await() }
            if (resetZoom) {
                // Keep the viewfinder at the user's framing while the HAL streams the full field.
                previewZoomCompensation?.invoke(zoom)
                bounded("zoom 1x") { cameraControl.setZoomRatio(1f).await() }
                maxSkip = maxOf(maxSkip, 6)
            }
            val ready: (CaptureResult?) -> Boolean = if (resetZoom) {
                { r -> exposureReady(r) && unzoomed(r) }
            } else {
                exposureReady
            }

            val (burst, collected) = if (raw) {
                awaitResult(if (manualT != null) manualT / 1_000_000L * 3 + 1500L else 1500L, ready)
                captureRaw(cameraInfo, plan.frameCount, takeRaw!!)
            } else {
                val size = checkNotNull(analyzer.frameSize)
                val burst = Burst(size.width, size.height, plan.frameCount)
                try {
                    burst to // Full-size YUV streams can run well below 30 fps on some HALs.
                    withTimeout(maxOf(plan.estimatedCaptureMs * 3, plan.frameCount * SLOW_FRAME_MS) * frameStride + 4000L) {
                        analyzer.arm(burst, plan.frameCount, maxSkip, ready, frameStride).await()
                    }
                } catch (e: Throwable) {
                    analyzer.disarm()
                    val got = burst.frameCount
                    burst.close()
                    // A timeout is a CancellationException; callers would treat it as a cancelled
                    // shot and never leave the capturing state, so report it as a failure.
                    if (e is TimeoutCancellationException) {
                        throw IllegalStateException("Burst timed out with $got of ${plan.frameCount} frames", e)
                    }
                    throw e
                }
            }
            val crop = MultiFrameProcessor.zoomCrop(burst.width, burst.height, cropZoom)
            return CapturedBurst(
                burst, plan, collected, SystemClock.elapsedRealtime() - start, crop, if (raw) "RAW" else "YUV",
            )
        } finally {
            runCatching { c2.setCaptureRequestOptions(savedOptions) }
            if (cameraInfo.exposureState.exposureCompensationIndex != savedEvIndex) {
                runCatching { cameraControl.setExposureCompensationIndex(savedEvIndex) }
            }
            if (resetZoom) {
                runCatching { bounded("zoom restore") { cameraControl.setZoomRatio(zoom).await() } }
                previewZoomCompensation?.invoke(1f)
            }
        }
    }

    /**
     * CameraX control futures can stay pending (e.g. an EV change the camera-pipe backend never
     * confirms); the frame checks after them verify the state anyway, so never wait forever.
     */
    private suspend fun bounded(what: String, block: suspend () -> Unit) {
        if (withTimeoutOrNull(CONTROL_TIMEOUT_MS) { block() } == null) {
            Log.w(LOG_TAG, "$what not confirmed within $CONTROL_TIMEOUT_MS ms, continuing")
        }
    }

    private fun unzoomed(r: CaptureResult?): Boolean {
        r ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            r.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { return it < 1.05f }
        }
        return true
    }

    /** Polls the session results until [ready] or [timeoutMs] passes (then goes ahead anyway). */
    private suspend fun awaitResult(timeoutMs: Long, ready: (CaptureResult?) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end && !ready(resultTracker.latest)) delay(30)
    }

    private suspend fun captureRaw(
        cameraInfo: CameraInfo,
        count: Int,
        takeRaw: suspend () -> ImageProxy,
    ): Pair<Burst, BurstAnalyzer.Collected> {
        var burst: Burst? = null
        var first: CaptureResult? = null
        var rotation = 0
        try {
            for (i in 0 until count) {
                val image = takeRaw()
                try {
                    check(image.format == ImageFormat.RAW_SENSOR) { "Not a RAW frame: ${image.format}" }
                    val result = BurstAnalyzer.captureResult(image)
                        ?: resultTracker.find(image.imageInfo.timestamp)
                        ?: resultTracker.latest
                    val params = checkNotNull(rawParams(cameraInfo, result)) { "No RAW metadata" }
                    val b = burst ?: Burst(image.width, image.height, count).also { burst = it }
                    val plane = image.planes[0]
                    val added = withContext(Dispatchers.Default) {
                        b.addRawFrame(plane.buffer, plane.rowStride, params)
                    }
                    check(added) { "RAW frame $i rejected" }
                    if (i == 0) {
                        first = result
                        rotation = image.imageInfo.rotationDegrees
                    }
                } finally {
                    image.close()
                }
            }
            return burst!! to BurstAnalyzer.Collected(rotation, first)
        } catch (e: Throwable) {
            burst?.close()
            throw e
        }
    }

    /** Development parameters from the camera characteristics and the frame's result. */
    private fun rawParams(cameraInfo: CameraInfo, result: CaptureResult?): RawDevelopParams? =
        rawParams(cameraCharacteristics(Camera2CameraInfo.from(cameraInfo).cameraId), result)

    private fun cameraCharacteristics(id: String): CameraCharacteristics =
        context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)

    private fun rawParams(info: CameraCharacteristics, result: CaptureResult?): RawDevelopParams? {
        result ?: return null
        val cfa = info.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?.takeIf { it in 0..3 } ?: return null
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size == 4 }
            ?: info.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                FloatArray(4) { p.getOffsetForIndex(it % 2, it / 2).toFloat() }
            } ?: return null
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat()
            ?: info.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat()
            ?: return null
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return null
        val ccm = FloatArray(9) { transform.getElement(it % 3, it / 3).toFloat() }
        val lsc = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val lscData = lsc?.let { m ->
            FloatArray(m.rowCount * m.columnCount * 4) {
                val cell = it / 4
                m.getGainFactor(it % 4, cell % m.columnCount, cell / m.columnCount)
            }
        }
        return RawDevelopParams(
            cfa = cfa,
            black = black,
            white = white,
            whiteBalance = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue),
            colorMatrix = ccm,
            lensShading = lscData,
            lensShadingWidth = lsc?.columnCount ?: 0,
            lensShadingHeight = lsc?.rowCount ?: 0,
        )
    }

    /**
     * Captures [plan] as one Camera2 burst (RAW, else full-size YUV) in a short session of its
     * own: CameraX is unbound meanwhile and bound again before returning. Exposure is manual per
     * frame, up to the sensor maximum. Throws on failure; the caller falls back.
     */
    suspend fun captureCamera2(cameraInfo: CameraInfo, plan: BurstPlan): CapturedBurst {
        val host = checkNotNull(burstHost) { "No activity to hand the camera over" }
        val id = Camera2CameraInfo.from(cameraInfo).cameraId
        val chars = cameraCharacteristics(id)
        check(Camera2Burst.supportsManual(chars)) { "Camera $id has no manual sensor control" }
        val outputs = listOfNotNull(
            Camera2Burst.largest(chars, ImageFormat.RAW_SENSOR)?.let { Camera2Burst.Output(ImageFormat.RAW_SENSOR, it) },
            Camera2Burst.largest(chars, ImageFormat.YUV_420_888)?.let { Camera2Burst.Output(ImageFormat.YUV_420_888, it) },
        )
        check(outputs.isNotEmpty()) { "No still output" }
        val minFrameNs = outputs.maxOf { Camera2Burst.minFrameDurationNs(chars, it.format, it.size) }
        val meter = analyzer.lastMeter?.takeIf { it.measured }
        val frames = BurstSequence.frames(plan, exposureCaps(cameraInfo), meter?.exposureTimeNs, meter?.iso, minFrameNs)
        check(frames.isNotEmpty()) { "No exposure for a ${plan.kind} burst" }
        val latest = resultTracker.latest
        val focusable = (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        val look = Camera2Burst.Look(
            latest?.get(CaptureResult.COLOR_CORRECTION_GAINS),
            latest?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
            latest?.get(CaptureResult.LENS_FOCUS_DISTANCE)?.takeIf { focusable },
        )
        val zoom = cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val rotation = analyzer.lastRotation
        val timeoutMs = BurstSequence.timeoutMs(frames)
        Log.i(
            LOG_TAG, "Camera2 ${plan.kind} burst: ${frames.size} x ${frames[0].exposureTimeNs} ns ISO ${frames[0].iso}, " +
                "expected ${BurstSequence.expectedMs(frames)} ms, timeout $timeoutMs ms",
        )
        val start = SystemClock.elapsedRealtime()
        var burst: Burst? = null
        var first: CaptureResult? = null
        captureProgress.value = 0f
        _cancellable.value = true
        host.pause()
        try {
            val format = Camera2Burst(context.getSystemService(CameraManager::class.java)).run(
                id, outputs, frames, look, timeoutMs,
            ) { index, format, image, result ->
                val added = withContext(Dispatchers.Default) {
                    if (index == 0) {
                        // A RAW attempt that failed part-way leaves frames behind; the YUV retry starts over.
                        burst?.close()
                        burst = Burst(image.width, image.height, frames.size)
                        first = result
                    }
                    val b = checkNotNull(burst)
                    val planes = image.planes
                    if (format == ImageFormat.RAW_SENSOR) {
                        val params = checkNotNull(rawParams(chars, result)) { "No RAW metadata" }
                        b.addRawFrame(planes[0].buffer, planes[0].rowStride, params)
                    } else {
                        b.addFrame(
                            planes[0].buffer, planes[0].rowStride,
                            planes[1].buffer, planes[2].buffer, planes[1].rowStride, planes[1].pixelStride,
                        )
                    }
                }
                check(added) { "Frame $index rejected" }
                Log.i(
                    LOG_TAG, "Frame $index: t=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
                        "iso=${result.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                        "fd=${result.get(CaptureResult.SENSOR_FRAME_DURATION)} " +
                        "at ${SystemClock.elapsedRealtime() - start} ms",
                )
                captureProgress.value = (index + 1f) / frames.size
            }
            val b = checkNotNull(burst)
            val crop = if (zoom > 1.05f) MultiFrameProcessor.zoomCrop(b.width, b.height, zoom) else null
            return CapturedBurst(
                b, plan, BurstAnalyzer.Collected(rotation, first), SystemClock.elapsedRealtime() - start, crop,
                if (format == ImageFormat.RAW_SENSOR) "RAW" else "YUV",
            )
        } catch (e: Throwable) {
            burst?.close()
            throw e
        } finally {
            _cancellable.value = false
            captureProgress.value = null
            host.resume()
        }
    }

    /**
     * Queues merging and saving; [onSaved] runs on the main thread. The burst is archived to
     * the cache while it merges, so a merge cut short by process death resumes on next launch.
     */
    fun process(captured: CapturedBurst, location: Location?, onSaved: (Uri?) -> Unit) {
        val timestamp = System.currentTimeMillis()
        val job = MergeJob.of(
            captured.plan.kind, captured.plan.toneEvStep, captured.crop, captured.collected.rotationDegrees,
            timestamp, captured.collected.captureResult?.let(ExposureExif::from), location, captured.source,
        )
        Log.i(
            LOG_TAG,
            "Captured kind=${captured.plan.kind} frames=${captured.burst.frameCount} " +
                "size=${captured.burst.width}x${captured.burst.height} source=${captured.source} " +
                "t=${captured.plan.exposureTimeNs} iso=${captured.plan.iso} ev=${captured.plan.evCompensation} " +
                "capture=${captured.captureMs}ms measured=${analyzer.lastMeter?.measured}"
        )
        queue.submit {
            var uri: Uri? = null
            val dir = if (captured.plan.kind != BurstKind.SINGLE) BurstRecovery.newDir(context, timestamp) else null
            try {
                captured.burst.use { burst ->
                    coroutineScope {
                        val archived = dir?.let { d -> async(Dispatchers.IO) { BurstRecovery.archive(burst, d, job) } }
                        _progress.value = 0f
                        uri = MergeRunner.run(context, burst, job) { _progress.value = it }
                        // Frames are read by the archiver until it is done; keep the burst open.
                        archived?.await()
                    }
                }
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "Multi-frame processing failed", e)
            } finally {
                _progress.value = null
                if (uri != null && dir != null) BurstArchive.delete(dir)
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

    fun framesThatFit(width: Int, height: Int, wanted: Int): Int {
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
        private const val CONTROL_TIMEOUT_MS = 1000L
        private const val SLOW_FRAME_MS = 500L
        private const val LOG_TAG = "MultiFrame"
        private const val KEY_SETTING = "multi_frame_setting"
        private const val KEY_SOURCE = "multi_frame_source"
        private const val MAX_PENDING_JOBS = 2
        private const val MIN_BURST_PIXELS = 6_000_000L
    }
}
