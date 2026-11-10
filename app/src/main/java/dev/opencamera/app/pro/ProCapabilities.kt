/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/**
 * What the bound camera lets Pro mode control, read from [CameraCharacteristics].
 */
class ProCapabilities(
    val cameraId: String,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val raw: Boolean,
    val ranges: ProRanges,
    val evStep: Float,
    val fNumber: Float?,
    val maxAeRegions: Int,
    val activeArray: Rect?,
    val awbModes: Set<Int>,
    val cctMode: Boolean,
    val calibration: WhiteBalanceMath.Calibration?,
) {
    val manualExposure get() = ranges.iso != null && ranges.exposureNs != null
    val manualFocus get() = ranges.minFocusDiopters > 0f
    val kelvin get() = cctMode || (manualPostProcessing && calibration?.forwardMatrix1 != null)

    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun from(cameraInfo: CameraInfo): ProCapabilities {
            val info = Camera2CameraInfo.from(cameraInfo)
            fun <T> get(key: CameraCharacteristics.Key<T>): T? = info.getCameraCharacteristic(key)

            val caps = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val manualSensor =
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
            val manualPost =
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps

            val iso = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                ?.takeIf { manualSensor }?.let { it.lower..it.upper }
            val exposure = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?.takeIf { manualSensor }?.let { it.lower..it.upper }
            val minFocus = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.takeIf { manualSensor } ?: 0f
            val evRange = cameraInfo.exposureState.exposureCompensationRange
            val evStep = cameraInfo.exposureState.exposureCompensationStep.toFloat()

            val cctMode = Build.VERSION.SDK_INT >= 36 &&
                    get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
                        ?.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT) == true

            val cm1 = get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toArray()
            val cm2 = get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toArray()
            val illum1 = get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
            val illum2 = get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
            val calibration = if (cm1 != null && illum1 != null) {
                WhiteBalanceMath.Calibration(
                    colorMatrix1 = cm1,
                    colorMatrix2 = cm2 ?: cm1,
                    forwardMatrix1 = get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.toArray(),
                    forwardMatrix2 = get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.toArray(),
                    cct1 = illuminantCct(illum1),
                    cct2 = illum2?.let { illuminantCct(it) } ?: illuminantCct(illum1),
                )
            } else {
                null
            }

            return ProCapabilities(
                cameraId = info.cameraId,
                manualSensor = manualSensor,
                manualPostProcessing = manualPost,
                raw = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps,
                ranges = ProRanges(
                    iso = iso,
                    exposureNs = exposure,
                    minFocusDiopters = minFocus,
                    ev = evRange.lower..evRange.upper,
                ),
                evStep = evStep,
                fNumber = get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
                maxAeRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                activeArray = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                awbModes = get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                    ?.toSet().orEmpty(),
                cctMode = cctMode,
                calibration = calibration,
            )
        }

        private fun ColorSpaceTransform.toArray() =
            DoubleArray(9) { getElement(it % 3, it / 3).toDouble() }

        /** Correlated colour temperature of an EXIF light source used for calibration. */
        fun illuminantCct(illuminant: Int) = when (illuminant) {
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> 4230.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4874.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH -> 5503.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5050.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6430.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> 6774.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6500.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> 7504.0
            else -> 6504.0
        }
    }
}
