/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

// Preview needs at least this frame duration; longer exposures stretch the frame.
private const val MIN_FRAME_DURATION_NS = 33_333_333L

/**
 * Adds the Camera2 request keys for [settings] to this builder. Settings must already be
 * clamped to [caps].
 */
@ExperimentalCamera2Interop
fun CaptureRequestOptions.Builder.applyPro(
    settings: ProSettings,
    caps: ProCapabilities,
): CaptureRequestOptions.Builder = apply {
    if (settings.manualExposure) {
        setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso!!)
        setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureNs!!)
        setCaptureRequestOption(
            CaptureRequest.SENSOR_FRAME_DURATION,
            maxOf(settings.exposureNs, MIN_FRAME_DURATION_NS),
        )
    } else {
        setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, settings.evIndex,
        )
        setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, settings.aeLock)
        meteringRegion(settings.metering, caps)?.let {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
    }

    applyWhiteBalance(settings, caps)

    settings.focusDiopters?.let {
        setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
    }
}

@ExperimentalCamera2Interop
private fun CaptureRequestOptions.Builder.applyWhiteBalance(
    settings: ProSettings,
    caps: ProCapabilities,
) {
    val mode = if (settings.wbMode == WbMode.KELVIN && !caps.kelvin) {
        WhiteBalanceMath.nearestPreset(settings.kelvin)
    } else {
        settings.wbMode
    }
    val awb = when (mode) {
        WbMode.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        WbMode.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WbMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        WbMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WbMode.CLOUDY -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WbMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        WbMode.KELVIN -> CameraMetadata.CONTROL_AWB_MODE_OFF
    }
    if (mode != WbMode.KELVIN) {
        setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            awb.takeIf { it in caps.awbModes } ?: CameraMetadata.CONTROL_AWB_MODE_AUTO,
        )
        return
    }

    if (caps.cctMode && Build.VERSION.SDK_INT >= 36) {
        setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awb)
        setCaptureRequestOption(
            CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT,
        )
        setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, settings.kelvin)
        return
    }

    val calibration = caps.calibration ?: return
    val kelvin = settings.kelvin.toDouble()
    val gains = WhiteBalanceMath.gains(kelvin, calibration)
    val transform = WhiteBalanceMath.colorTransform(kelvin, calibration) ?: return
    setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awb)
    setCaptureRequestOption(
        CaptureRequest.COLOR_CORRECTION_MODE,
        CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
    )
    setCaptureRequestOption(
        CaptureRequest.COLOR_CORRECTION_GAINS,
        RggbChannelVector(gains[0], gains[1], gains[2], gains[3]),
    )
    setCaptureRequestOption(
        CaptureRequest.COLOR_CORRECTION_TRANSFORM,
        ColorSpaceTransform(IntArray(18) {
            if (it % 2 == 0) (transform[it / 2] * RATIONAL_DENOMINATOR).toInt()
            else RATIONAL_DENOMINATOR
        }),
    )
}

private const val RATIONAL_DENOMINATOR = 10000

private fun meteringRegion(mode: MeteringMode, caps: ProCapabilities): MeteringRectangle? {
    if (caps.maxAeRegions < 1) return null
    val array = caps.activeArray ?: return null
    val fraction = when (mode) {
        MeteringMode.SPOT -> 0.08f
        MeteringMode.CENTER -> 0.4f
        MeteringMode.MATRIX -> 1f
    }
    val w = (array.width() * fraction).toInt()
    val h = (array.height() * fraction).toInt()
    return MeteringRectangle(
        (array.width() - w) / 2, (array.height() - h) / 2, w, h,
        MeteringRectangle.METERING_WEIGHT_MAX,
    )
}
