/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

data class BuildInfo(
    val brand: String,
    val device: String,
    val model: String,
    val fingerprint: String,
    val sdkInt: Int,
)

object ProfileFactory {
    fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    /** Builds an `auto` profile from scanned characteristics. */
    fun autoProfile(build: BuildInfo, specs: List<CameraSpec>): DeviceProfile {
        val (cameras, quirks) = LensCatalog.build(specs)
        return DeviceProfile(
            id = "${normalize(build.brand)}/${normalize(build.device)}",
            match = ProfileMatch(
                brand = normalize(build.brand),
                device = listOf(build.device),
                model = listOf(build.model),
            ),
            cameras = cameras,
            zoomStops = LensCatalog.zoomStops(cameras, quirks),
            quirks = quirks,
            source = ProfileSource.AUTO,
            testedBuilds = listOf(build.fingerprint),
        )
    }
}