/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import dev.opencamera.pipeline.OpenCv
import kotlin.math.max

/** Per-camera facts the video modes need beyond [VideoModeCaps]. */
data class CameraFacts(
    val cameraId: String?,
    val qualities: List<Quality>,
    val regularFps: List<Int>,
    val hdrRanges: List<DynamicRange>,
    val halStabilization: Boolean,
    val previewStabilization: Boolean,
    val toneCurve: Boolean,
    val maxToneCurvePoints: Int,
    val highSpeedQualities: List<Quality>,
    val highSpeedRanges: List<Range<Int>>,
    /** Recorder capability source that produced [highSpeedQualities]. */
    val highSpeedSource: Int,
    /** Focal length over the sensor's long side; times the frame's long side gives pixels. */
    val focalOverSensor: Float,
    val timestampRealtime: Boolean,
)

@OptIn(ExperimentalCamera2Interop::class)
object VideoCapsProbe {
    private const val TAG = "VideoCapsProbe"

    fun selector(back: Boolean) =
        if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

    fun cameraInfo(provider: ProcessCameraProvider, back: Boolean): CameraInfo? = try {
        provider.getCameraInfo(selector(back))
    } catch (e: Exception) {
        null
    }

    // The source-taking overload is library-restricted; it is the only way to query codec-based
    // high-speed qualities. Failures fall back to the Camera2 high-speed path.
    @SuppressLint("RestrictedApi")
    fun facts(info: CameraInfo): CameraFacts {
        val c2 = runCatching { Camera2CameraInfo.from(info) }.getOrNull()
        fun <T> key(k: CameraCharacteristics.Key<T>): T? = runCatching { c2?.getCameraCharacteristic(k) }.getOrNull()

        val caps = runCatching { Recorder.getVideoCapabilities(info) }.getOrNull()
        val qualities = runCatching { caps?.getSupportedQualities(DynamicRange.SDR) }.getOrNull().orEmpty()
        val hdr = runCatching {
            caps?.supportedDynamicRanges.orEmpty().filter { it != DynamicRange.SDR && it.bitDepth == 10 }
        }.getOrNull().orEmpty()
        val regular = runCatching { info.supportedFrameRateRanges }.getOrNull().orEmpty()
            .filter { it.lower == it.upper }.map { it.upper }.distinct().sorted()
        val stabModes = key(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val toneModes = key(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        // Many ROMs ship no high-speed camcorder profiles; the codec source still knows the sizes.
        var hsSource = Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE
        fun hsQualitiesFor(source: Int) = runCatching {
            Recorder.getHighSpeedVideoCapabilities(info, source)?.getSupportedQualities(DynamicRange.SDR)
        }.getOrNull().orEmpty()
        val hsQualities = hsQualitiesFor(hsSource).ifEmpty {
            hsSource = Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES
            hsQualitiesFor(hsSource)
        }
        val hsRanges = runCatching {
            key(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoFpsRanges?.toList()
        }.getOrNull().orEmpty().filter { it.lower == it.upper }.distinctBy { it.upper }.sortedBy { it.upper }
        val focal = key(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val physical = key(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val longSide = physical?.let { max(it.width, it.height) } ?: 0f
        val focalOverSensor = if (focal > 0f && longSide > 0f) focal / longSide else 0.8f

        return CameraFacts(
            cameraId = c2?.cameraId,
            qualities = qualities,
            regularFps = regular,
            hdrRanges = hdr,
            halStabilization = stabModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true,
            previewStabilization = stabModes?.contains(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION,
            ) == true,
            toneCurve = toneModes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true,
            maxToneCurvePoints = key(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0,
            highSpeedQualities = hsQualities,
            highSpeedRanges = hsRanges,
            highSpeedSource = hsSource,
            focalOverSensor = focalOverSensor,
            timestampRealtime = key(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        )
    }

    fun caps(context: Context, provider: ProcessCameraProvider, facts: CameraFacts?): VideoModeCaps {
        val sensors = context.getSystemService(SensorManager::class.java)
        val hasGyro = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val concurrent = runCatching {
            provider.availableConcurrentCameraInfos.any { combo ->
                combo.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                    combo.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            }
        }.getOrDefault(false)
        val highSpeed = when {
            facts == null -> 0
            facts.highSpeedQualities.isNotEmpty() -> facts.highSpeedRanges.maxOfOrNull { it.upper } ?: 0
            else -> facts.cameraId?.let { id -> HighSpeedSession.configs(context, id).maxOfOrNull { it.fps } } ?: 0
        }
        return VideoModeCaps(
            canRecord = facts?.qualities?.isNotEmpty() == true,
            canAnalyze = facts != null,
            highSpeedFps = highSpeed,
            maxRegularFps = facts?.regularFps?.maxOrNull() ?: 0,
            hasGyro = hasGyro,
            hasConcurrentFrontBack = concurrent,
            openCv = OpenCv.available,
            hasSegmenter = VideoSegmenters.current != null,
        ).also { Log.i(TAG, "caps $it facts $facts") }
    }
}
