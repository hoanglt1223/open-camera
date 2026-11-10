/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

enum class Facing(val key: String) {
    BACK("back"),
    FRONT("front"),
    EXTERNAL("external");

    companion object {
        fun fromKey(key: String) = entries.first { it.key == key }
    }
}

enum class Capability {
    BACKWARD_COMPATIBLE,
    LOGICAL_MULTI_CAMERA,
    MONOCHROME,
    DEPTH_OUTPUT,
    RAW,
}

/**
 * Static characteristics of one camera id, as read from Camera2.
 * [minFocusDistance] is in diopters; 0 means fixed focus.
 */
data class CameraSpec(
    val id: String,
    val facing: Facing,
    val focalLengthsMm: List<Float>,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val minFocusDistance: Float = 0f,
    val capabilities: Set<Capability> = setOf(Capability.BACKWARD_COMPATIBLE),
    val physicalIds: List<String> = emptyList(),
    val hidden: Boolean = false,
) {
    val isLogical get() = Capability.LOGICAL_MULTI_CAMERA in capabilities
    val megapixels get() = pixelWidth.toLong() * pixelHeight / 1_000_000f
    val sensorAreaMm2 get() = sensorWidthMm * sensorHeightMm
}