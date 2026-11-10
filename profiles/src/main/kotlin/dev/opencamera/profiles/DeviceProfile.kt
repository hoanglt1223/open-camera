/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

enum class LensRole(val key: String) {
    MAIN("main"),
    ULTRA("ultra"),
    TELE("tele"),
    MACRO("macro"),
    DEPTH("depth"),
    MONO("mono"),
    FRONT("front"),
    FRONT_ULTRA("front-ultra"),
    LOGICAL("logical");

    companion object {
        fun fromKey(key: String) = entries.first { it.key == key }
    }
}

enum class StopMode(val key: String) {
    PHYSICAL("physical"),
    CROP("crop"),
    LOGICAL("logical");

    companion object {
        fun fromKey(key: String) = entries.first { it.key == key }
    }
}

enum class ProfileSource(val key: String, val priority: Int) {
    AUTO("auto", 0),
    COMMUNITY("community", 1),
    VERIFIED("verified", 2);

    companion object {
        fun fromKey(key: String) = entries.first { it.key == key }
    }
}

data class ProfileMatch(
    val brand: String,
    val device: List<String> = emptyList(),
    val model: List<String> = emptyList(),
    val fingerprintPrefix: List<String> = emptyList(),
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
)

data class CameraEntry(
    val id: String,
    val facing: Facing,
    val role: LensRole,
    val focalMm: Float,
    val equivFocalMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val physicalIds: List<String> = emptyList(),
    val duplicateOf: String? = null,
    val hidden: Boolean = false,
)

data class ZoomStop(
    val ratio: Float,
    val cameraId: String,
    val mode: StopMode,
    val label: String,
)

data class DeviceProfile(
    val id: String,
    val match: ProfileMatch,
    val cameras: List<CameraEntry>,
    val zoomStops: List<ZoomStop>,
    val quirks: Set<Quirk> = emptySet(),
    val source: ProfileSource = ProfileSource.AUTO,
    val testedBuilds: List<String> = emptyList(),
    val knownBugs: List<String> = emptyList(),
    val vendorTags: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}