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
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.params.ColorSpaceTransform
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.StringRes
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.core.content.edit
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import dev.opencamera.app.R
import dev.opencamera.capture.BurstKind
import dev.opencamera.capture.BurstPlan
import dev.opencamera.capture.BurstSequence
import dev.opencamera.pipeline.Burst
import dev.opencamera.pipeline.RawDevelopParams
import dev.opencamera.pipeline.RawMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.log2

/**
 * Pro "RAW merged": a RAW_SENSOR burst at the preview's exposure through a Camera2 session of its
 * own, merged on the Bayer mosaic as the frames arrive and saved as a demosaiced linear DNG,
 * plus a JPEG developed from the same merge. Falls back to a single-frame DNG through
 * [DngCreator], then to JPEG only; every fallback is logged and noticed once per reason.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MergedRawCapture(
    private val context: Context,
    private val multiFrame: MultiFrameCaptureController,
) {
    /** Set by Pro mode: RAW merged is the selected format and Pro is active. */
    @Volatile
    var enabled = false

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** One-time notices (string resources) when a merged-RAW shot falls back. */
    val notices: SharedFlow<Int> = _notices.asSharedFlow()

    private var saving: Job? = null

    /** Frames and metadata of one shot; owns native memory until [save] finishes or [close]. */
    class Shot internal constructor(
        val plan: MergedRawPolicy.Plan,
        val chars: CameraCharacteristics,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val captureMs: Long,
        internal var merger: RawMerger?,
        internal var reference: ByteBuffer?,
        internal val referenceResult: CaptureResult?,
        internal val referenceParams: RawDevelopParams?,
        internal val failure: Pair<RawFallbackReason, Throwable>?,
        val peakNativeBytes: Long,
    ) : AutoCloseable {
        val referenceCaptured get() = reference != null && referenceResult != null && referenceParams != null

        override fun close() {
            merger?.close()
            merger = null
            reference = null
        }
    }

    /** True when the bound camera can take a merged RAW burst at all. */
    fun supported(cameraInfo: CameraInfo): Boolean = runCatching {
        val chars = multiFrame.cameraCharacteristics(Camera2CameraInfo.from(cameraInfo).cameraId)
        supported(chars)
    }.getOrDefault(false)

    private fun supported(chars: CameraCharacteristics): Boolean =
        multiFrame.burstHost != null &&
            Camera2Burst.supportsManual(chars) &&
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true &&
            chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) in 0..3 &&
            Camera2Burst.largest(chars, ImageFormat.RAW_SENSOR) != null

    /**
     * Takes the burst. Returns null when no RAW frame could be taken (the caller then takes a
     * regular JPEG); otherwise a shot to pass to [save]. Cancellation propagates.
     */
    suspend fun capture(cameraInfo: CameraInfo): Shot? {
        // One shot's buffers at a time: the memory guard assumes nothing else is held.
        saving?.let { withTimeoutOrNull(SAVE_WAIT_MS) { it.join() } }
        val start = SystemClock.elapsedRealtime()
        val id = Camera2CameraInfo.from(cameraInfo).cameraId
        val chars = multiFrame.cameraCharacteristics(id)
        val size = Camera2Burst.largest(chars, ImageFormat.RAW_SENSOR)
        val supported = runCatching { supported(chars) }.getOrDefault(false)
        val minFrameNs = size?.let { Camera2Burst.minFrameDurationNs(chars, ImageFormat.RAW_SENSOR, it) } ?: 0L
        val latest = multiFrame.resultTracker.latest
        val meteredT = latest?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: multiFrame.analyzer.lastMeter?.exposureTimeNs
        val meteredIso = latest?.get(CaptureResult.SENSOR_SENSITIVITY) ?: multiFrame.analyzer.lastMeter?.iso
        val exposure = BurstSequence.frames(
            BurstPlan(BurstKind.SINGLE, 1), multiFrame.exposureCaps(cameraInfo), meteredT, meteredIso, minFrameNs,
        ).firstOrNull()
        val plan = MergedRawPolicy.plan(
            supported && exposure != null, availableBytes(), size?.width ?: 0, size?.height ?: 0,
            exposure?.frameDurationNs ?: 0L,
        )
        plan.reason?.let { report(RawOutput.MERGED_DNG, it, IllegalStateException("plan $plan")) }
        if (plan.output == RawOutput.JPEG_ONLY || size == null || exposure == null) return null
        val frames = List(plan.frames) { exposure }

        val focusable = (chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        val look = Camera2Burst.Look(
            latest?.get(CaptureResult.COLOR_CORRECTION_GAINS),
            latest?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM),
            latest?.get(CaptureResult.LENS_FOCUS_DISTANCE)?.takeIf { focusable },
        )
        val rotation = multiFrame.analyzer.lastRotation
        val timeoutMs = BurstSequence.timeoutMs(frames) + frames.size * MergedRawPolicy.PROCESS_MS_PER_FRAME
        Log.i(
            LOG_TAG, "Merged RAW: ${frames.size} x ${exposure.exposureTimeNs} ns ISO ${exposure.iso} at " +
                "${size.width}x${size.height}, plan $plan, timeout $timeoutMs ms",
        )

        var merger: RawMerger? = null
        var reference: ByteBuffer? = null
        var refResult: CaptureResult? = null
        var refParams: RawDevelopParams? = null
        var failure: Pair<RawFallbackReason, Throwable>? = null
        var peak = usedBytes()
        try {
            multiFrame.withCameraHandOver {
                Camera2Burst(context.getSystemService(CameraManager::class.java)).run(
                    id, listOf(Camera2Burst.Output(ImageFormat.RAW_SENSOR, size)), frames, look, timeoutMs,
                ) { index, _, image, result ->
                    withContext(Dispatchers.Default) {
                        val plane = image.planes[0]
                        val params = multiFrame.rawParams(chars, result)
                        if (index == 0) {
                            checkNotNull(params) { "No RAW metadata" }
                            reference = compactCopy(plane.buffer, plane.rowStride, image.width, image.height)
                            refResult = result
                            refParams = params
                            if (plan.output == RawOutput.MERGED_DNG) {
                                merger = try {
                                    RawMerger.begin(image.width, image.height, reference!!, image.width * 2, params)
                                } catch (e: Throwable) {
                                    failure = RawFallbackReason.MERGE to e
                                    null
                                }
                            }
                        } else {
                            merger?.let { m ->
                                try {
                                    val black = checkNotNull(params) { "No RAW metadata for frame $index" }.black
                                    val weight = m.add(plane.buffer, plane.rowStride, black)
                                    Log.i(LOG_TAG, "Frame $index merged, weight %.2f".format(weight))
                                } catch (e: Throwable) {
                                    failure = RawFallbackReason.MERGE to e
                                    m.close()
                                    merger = null
                                }
                            }
                        }
                        peak = maxOf(peak, usedBytes())
                    }
                    Log.i(LOG_TAG, "Frame $index at ${SystemClock.elapsedRealtime() - start} ms")
                    multiFrame.reportCaptureProgress((index + 1f) / frames.size)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            merger?.close()
            throw e
        } catch (e: Throwable) {
            // Frames taken before the failure are still used: a partial merge or the reference.
            Log.w(LOG_TAG, "Merged RAW burst stopped after ${merger?.frameCount ?: if (reference != null) 1 else 0} frames", e)
            if (failure == null) failure = RawFallbackReason.CAPTURE to e
        }
        val shot = Shot(
            plan, chars, size.width, size.height, rotation, SystemClock.elapsedRealtime() - start,
            merger, reference, refResult, refParams, failure, peak,
        )
        if (!shot.referenceCaptured) {
            shot.close()
            report(RawOutput.MERGED_DNG, failure?.first ?: RawFallbackReason.CAPTURE, failure?.second ?: IllegalStateException("No frame"))
            return null
        }
        return shot
    }

    /**
     * Writes the DNG (merged, else single) and queues the JPEG; [onSaved] gets the JPEG's uri on
     * the main thread. Consumes [shot].
     */
    suspend fun save(shot: Shot, location: Location?, onSaved: (Uri?) -> Unit): RawOutput {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        saving = job
        try {
            return withContext(Dispatchers.IO) { saveNow(shot, location, onSaved) }
        } finally {
            if (saving === job) saving = null
        }
    }

    private suspend fun saveNow(shot: Shot, location: Location?, onSaved: (Uri?) -> Unit): RawOutput {
        shot.use {
            val timestamp = System.currentTimeMillis()
            val start = SystemClock.elapsedRealtime()
            val mergedFrames = shot.merger?.frameCount ?: 0
            shot.failure?.let { (reason, e) ->
                if (MergedRawPolicy.start(shot.plan, mergedFrames, true) != shot.plan.output) report(shot.plan.output, reason, e)
                else Log.w(LOG_TAG, "Merged RAW continues after: $reason", e)
            }
            var finishMs = 0L
            val output = RawFallbackChain.run(
                MergedRawPolicy.start(shot.plan, mergedFrames, shot.referenceCaptured),
                mergedDng = {
                    val m = checkNotNull(shot.merger)
                    val t = SystemClock.elapsedRealtime()
                    m.finish()
                    finishMs = SystemClock.elapsedRealtime() - t
                    writeMergedDng(shot, m, timestamp)
                },
                singleDng = { writeSingleDng(shot, location, timestamp) },
                onFailure = { failed, reason, e -> report(failed, reason, e) },
            )
            val dngMs = SystemClock.elapsedRealtime() - start
            val burst = Burst(shot.width, shot.height, 1)
            val developed = runCatching {
                val params = checkNotNull(shot.referenceParams)
                if (output == RawOutput.MERGED_DNG) {
                    burst.addMergedRaw(checkNotNull(shot.merger), params)
                } else {
                    burst.addRawFrame(checkNotNull(shot.reference), shot.width * 2, params)
                }
            }.onFailure { Log.e(LOG_TAG, "Developing the JPEG failed", it) }.getOrDefault(false)
            val peak = maxOf(shot.peakNativeBytes, usedBytes())
            shot.close()
            Log.i(
                LOG_TAG, "Merged RAW saved $output: frames=$mergedFrames capture=${shot.captureMs}ms " +
                    "finish=${finishMs}ms dng=${dngMs}ms peakHeap=${peak / (1 shl 20)}MiB",
            )
            if (!developed) {
                burst.close()
                withContext(Dispatchers.Main) { onSaved(null) }
                return output
            }
            val source = if (output == RawOutput.MERGED_DNG) "RAW merged x$mergedFrames" else "RAW"
            multiFrame.process(
                MultiFrameCaptureController.CapturedBurst(
                    burst, BurstPlan(BurstKind.SINGLE, 1),
                    BurstAnalyzer.Collected(shot.rotation, shot.referenceResult), shot.captureMs, null, source,
                ),
                location, ultraHdr = false, onSaved = onSaved,
            )
            return output
        }
    }

    private fun writeMergedDng(shot: Shot, merger: RawMerger, timestamp: Long) {
        val t = SystemClock.elapsedRealtime()
        val info = dngInfo(shot.chars, checkNotNull(shot.referenceResult), merger.width, merger.height,
            shot.rotation, timestamp, "Merged ${merger.frameCount} frames")
        val strip = ByteBuffer.allocateDirect(LinearDngWriter.DEFAULT_ROWS_PER_STRIP * merger.width * 6)
            .order(ByteOrder.nativeOrder())
        val uri = MultiFrameSaver.insert(context, DNG_MIME, timestamp) { out ->
            BufferedOutputStream(out, 1 shl 20).let { buffered ->
                LinearDngWriter.write(buffered, info) { y0, rows, bytes ->
                    strip.clear()
                    merger.readRgbRows(y0, rows, strip)
                    strip.get(bytes, 0, rows * merger.width * 6)
                }
                buffered.flush()
            }
        } ?: throw RawStepException(RawFallbackReason.MERGE, "MediaStore refused the DNG")
        Log.i(LOG_TAG, "Merged DNG $uri written in ${SystemClock.elapsedRealtime() - t} ms")
    }

    private fun writeSingleDng(shot: Shot, location: Location?, timestamp: Long) {
        val result = checkNotNull(shot.referenceResult)
        val raw = checkNotNull(shot.reference)
        val t = SystemClock.elapsedRealtime()
        DngCreator(shot.chars, result).use { dng ->
            dng.setOrientation(exifOrientation(shot.rotation))
            location?.let(dng::setLocation)
            dng.setDescription("Single frame")
            val uri = MultiFrameSaver.insert(context, DNG_MIME, timestamp) { out ->
                raw.rewind()
                dng.writeByteBuffer(out, Size(shot.width, shot.height), raw, 0)
            } ?: throw RawStepException(RawFallbackReason.SINGLE_DNG, "MediaStore refused the DNG")
            Log.i(LOG_TAG, "Single DNG $uri written in ${SystemClock.elapsedRealtime() - t} ms")
        }
    }

    private fun report(failed: RawOutput, reason: RawFallbackReason, error: Throwable) {
        Log.w(LOG_TAG, "Merged RAW: $failed not saved ($reason)", error)
        val key = "merged_raw_notice_${reason.name}"
        if (prefs.getBoolean(key, false)) return
        prefs.edit { putBoolean(key, true) }
        _notices.tryEmit(noticeFor(reason))
    }

    /** Native plus Java heap in use (direct buffers live in the Java heap on ART). */
    private fun usedBytes(): Long {
        val rt = Runtime.getRuntime()
        return Debug.getNativeHeapAllocatedSize() + rt.totalMemory() - rt.freeMemory()
    }

    private fun availableBytes(): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0L
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (info.lowMemory) return 0L
        // Half of what the system can give before it starts killing, as for the other bursts.
        return (info.availMem - info.threshold) / 2
    }

    companion object {
        private const val LOG_TAG = "MergedRaw"
        private const val DNG_MIME = "image/x-adobe-dng"
        private const val SAVE_WAIT_MS = 15_000L

        @StringRes
        fun noticeFor(reason: RawFallbackReason): Int = when (reason) {
            RawFallbackReason.UNSUPPORTED -> R.string.raw_merged_unsupported
            RawFallbackReason.LOW_MEMORY -> R.string.raw_merged_low_memory
            RawFallbackReason.CAPTURE -> R.string.raw_merged_capture_failed
            RawFallbackReason.MERGE -> R.string.raw_merged_failed
            RawFallbackReason.SINGLE_DNG -> R.string.raw_dng_failed
        }

        fun exifOrientation(rotationDegrees: Int): Int = when ((rotationDegrees % 360 + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

        /** Copies a RAW16 plane into a dense direct buffer (row stride = width x 2). */
        private fun compactCopy(src: ByteBuffer, rowStride: Int, width: Int, height: Int): ByteBuffer {
            val out = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
            val row = width * 2
            val s = src.duplicate()
            for (y in 0 until height) {
                s.limit(y * rowStride + row).position(y * rowStride)
                out.put(s)
            }
            out.rewind()
            return out
        }

        private fun ColorSpaceTransform.toFloats() = FloatArray(9) { getElement(it % 3, it / 3).toFloat() }

        fun dngInfo(
            chars: CameraCharacteristics,
            result: CaptureResult,
            width: Int,
            height: Int,
            rotationDegrees: Int,
            timestamp: Long,
            description: String,
        ): LinearDngInfo {
            val cm1 = checkNotNull(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)) { "No ColorMatrix1" }.toFloats()
            val illum1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 21
            val cm2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toFloats()
            val illum2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
            val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toFloat() }?.toFloatArray()
                ?.takeIf { n -> n.size == 3 && n.all { it > 0f } }
                ?: result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { g ->
                    floatArrayOf(g.greenEven / g.red, 1f, g.greenEven / g.blue)
                }
            val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.takeIf { it > 0 } ?: 100
            return LinearDngInfo(
                width = width,
                height = height,
                make = Build.MANUFACTURER,
                model = Build.MODEL,
                software = "Open Camera",
                orientation = exifOrientation(rotationDegrees),
                colorMatrix1 = cm1,
                illuminant1 = illum1,
                colorMatrix2 = cm2?.takeIf { illum2 != null },
                illuminant2 = illum2?.takeIf { cm2 != null },
                forwardMatrix1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toFloats(),
                forwardMatrix2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toFloats(),
                cameraCalibration1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toFloats(),
                cameraCalibration2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toFloats()
                    ?.takeIf { cm2 != null && illum2 != null },
                asShotNeutral = neutral,
                baselineExposure = log2(boost / 100f),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                fNumber = result.get(CaptureResult.LENS_APERTURE),
                focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                dateTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(timestamp),
                description = description,
            )
        }
    }
}
