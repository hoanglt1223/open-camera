/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

enum class WbMode { AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY, SHADE, KELVIN }

enum class MeteringMode { CENTER, SPOT, MATRIX }

/**
 * Manual controls for one lens. `null` means the camera picks the value.
 * Exposure is manual only when both [iso] and [exposureNs] are set, because Camera2 turns
 * auto exposure off for both at once.
 */
data class ProSettings(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val wbMode: WbMode = WbMode.AUTO,
    val kelvin: Int = 5500,
    val focusDiopters: Float? = null,
    val evIndex: Int = 0,
    val metering: MeteringMode = MeteringMode.CENTER,
    val aeLock: Boolean = false,
    val peaking: Boolean = false,
    val zebra: Boolean = false,
    val histogram: Boolean = true,
) {
    val manualExposure get() = iso != null && exposureNs != null
}

/**
 * Device limits used to clamp [ProSettings]. A `null` range means the control is unsupported.
 */
data class ProRanges(
    val iso: IntRange?,
    val exposureNs: LongRange?,
    val minFocusDiopters: Float,
    val ev: IntRange,
    val kelvin: IntRange = KELVIN_MIN..KELVIN_MAX,
) {
    companion object {
        const val KELVIN_MIN = 2000
        const val KELVIN_MAX = 10000
    }
}

fun ProSettings.clampTo(ranges: ProRanges): ProSettings {
    val manual = manualExposure && ranges.iso != null && ranges.exposureNs != null
    return copy(
        iso = if (manual) iso!!.coerceIn(ranges.iso!!) else null,
        exposureNs = if (manual) exposureNs!!.coerceIn(ranges.exposureNs!!) else null,
        kelvin = kelvin.coerceIn(ranges.kelvin),
        focusDiopters = focusDiopters?.takeIf { ranges.minFocusDiopters > 0f }
            ?.coerceIn(0f, ranges.minFocusDiopters),
        evIndex = if (manual) 0 else evIndex.coerceIn(ranges.ev),
    )
}
