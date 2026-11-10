/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

/** Picks the profile to use: user override, then verified > community > auto. */
object ProfileResolver {
    fun matches(profile: DeviceProfile, build: BuildInfo): Boolean {
        val m = profile.match
        val norm = ProfileFactory::normalize
        if (norm(m.brand) != norm(build.brand)) return false
        if (m.minSdk != null && build.sdkInt < m.minSdk) return false
        if (m.maxSdk != null && build.sdkInt > m.maxSdk) return false
        if (m.fingerprintPrefix.isNotEmpty() &&
            m.fingerprintPrefix.none { build.fingerprint.startsWith(it) }
        ) return false
        return m.device.any { norm(it) == norm(build.device) } ||
            m.model.any { norm(it) == norm(build.model) }
    }

    fun resolve(
        build: BuildInfo,
        auto: DeviceProfile,
        candidates: List<DeviceProfile> = emptyList(),
        override: DeviceProfile? = null,
    ): DeviceProfile = override
        ?: candidates.filter { matches(it, build) }.maxByOrNull { it.source.priority }
        ?: auto
}

/** Zoom stops in display order, with chip selection for an arbitrary zoom ratio. */
class LensPlan(val stops: List<ZoomStop>) {
    fun stopFor(zoomRatio: Float): ZoomStop? =
        stops.lastOrNull { it.ratio <= zoomRatio + 1e-4f } ?: stops.firstOrNull()

    companion object {
        fun from(profile: DeviceProfile) = LensPlan(profile.zoomStops.sortedBy { it.ratio })
    }
}