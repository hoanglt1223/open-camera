/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

/**
 * Picks the profile to use: user override file, then the profile pinned in Settings, then the
 * best bundled match (verified > community, device codename > model code), then `auto`.
 * Catalog matches are merged with the scanned `auto` profile.
 */
object ProfileResolver {
    /** Pinned id meaning "ignore the bundled database". */
    const val AUTO_ID = "auto"

    /** Brands that ship under one Build.BRAND or another depending on region. */
    private val BRAND_FAMILIES = listOf(
        setOf("xiaomi", "redmi", "poco"),
        setOf("vivo", "iqoo"),
    )

    private fun brandKey(brand: String): String {
        val norm = ProfileFactory.normalize(brand)
        return BRAND_FAMILIES.firstOrNull { norm in it }?.first() ?: norm
    }

    private fun deviceMatches(profile: DeviceProfile, build: BuildInfo) =
        profile.match.device.any { ProfileFactory.normalize(it) == ProfileFactory.normalize(build.device) }

    fun matches(profile: DeviceProfile, build: BuildInfo): Boolean {
        val m = profile.match
        val norm = ProfileFactory::normalize
        if (brandKey(m.brand) != brandKey(build.brand)) return false
        if (m.minSdk != null && build.sdkInt < m.minSdk) return false
        if (m.maxSdk != null && build.sdkInt > m.maxSdk) return false
        if (m.fingerprintPrefix.isNotEmpty() &&
            m.fingerprintPrefix.none { build.fingerprint.startsWith(it) }
        ) return false
        return deviceMatches(profile, build) || m.model.any { norm(it) == norm(build.model) }
    }

    fun bestMatch(build: BuildInfo, candidates: List<DeviceProfile>): DeviceProfile? =
        candidates.filter { matches(it, build) }.maxWithOrNull(
            compareBy<DeviceProfile> { it.source.priority }
                .thenBy { deviceMatches(it, build) }
                .thenBy { it.isMeasured }
        )

    fun resolve(
        build: BuildInfo,
        auto: DeviceProfile,
        candidates: List<DeviceProfile> = emptyList(),
        override: DeviceProfile? = null,
        pinnedId: String? = null,
    ): DeviceProfile {
        if (override != null) return ProfileMerger.merge(auto, override)
        if (pinnedId == AUTO_ID) return auto
        val pinned = pinnedId?.let { id -> candidates.firstOrNull { it.id == id } }
        return (pinned ?: bestMatch(build, candidates))?.let { ProfileMerger.merge(auto, it) } ?: auto
    }
}

object ProfileMerger {
    /**
     * A measured profile is used as is. A catalog profile keeps the scanned cameras and quirks
     * and takes its identity, source, published roles and stop labels from the catalog.
     */
    fun merge(auto: DeviceProfile, db: DeviceProfile): DeviceProfile {
        if (db.isMeasured || auto.cameras.isEmpty()) return db.takeIf { it.isMeasured } ?: auto
        val quirks = auto.quirks + db.quirks
        return auto.copy(
            id = db.id,
            name = db.name,
            match = db.match,
            lenses = db.lenses,
            zoomStops = LensCatalog.zoomStops(auto.cameras, quirks, db.lenses)
                .ifEmpty { auto.zoomStops },
            quirks = quirks,
            source = db.source,
            knownBugs = db.knownBugs,
            vendorTags = db.vendorTags + auto.vendorTags,
        )
    }
}

/**
 * One chip of the zoom selector: open [cameraId] and set [zoomRatio] on it.
 * [ratio] is the field of view relative to the main lens and orders the chips.
 */
data class LensChip(
    val label: String,
    val ratio: Float,
    val cameraId: String,
    val zoomRatio: Float,
)

/** Zoom stops in display order, with chip selection for an arbitrary zoom ratio. */
class LensPlan(val stops: List<ZoomStop>, private val cameras: List<CameraEntry> = emptyList()) {
    fun stopFor(zoomRatio: Float): ZoomStop? =
        stops.lastOrNull { it.ratio <= zoomRatio + 1e-4f } ?: stops.firstOrNull()

    /**
     * Maps stops onto cameras the app can open ([openableIds], i.e. what CameraX lists).
     * A hidden stop camera is replaced by an openable duplicate id, else by an openable
     * logical camera that contains it (zooming to the stop ratio); otherwise the stop is
     * dropped. Returns an empty list when the 1x stop cannot be shown.
     */
    fun chips(openableIds: Set<String>): List<LensChip> {
        val byId = cameras.associateBy { it.id }
        val baseRatio = stops.filter { it.mode == StopMode.PHYSICAL }.associate { it.cameraId to it.ratio }

        fun route(id: String): Pair<String, Boolean>? {
            if (id in openableIds) return id to false
            val canonical = byId[id]?.duplicateOf ?: id
            val twin = (listOf(canonical) + cameras.filter { it.duplicateOf == canonical }.map { it.id })
                .firstOrNull { it in openableIds }
            if (twin != null) return twin to false
            val logical = cameras.firstOrNull {
                it.role == LensRole.LOGICAL && it.id in openableIds && canonical in it.physicalIds
            }
            return logical?.let { it.id to true }
        }

        val chips = stops.mapNotNull { stop ->
            val (id, viaLogical) = route(stop.cameraId) ?: return@mapNotNull null
            val zoom = when {
                stop.mode == StopMode.LOGICAL || viaLogical -> stop.ratio
                stop.mode == StopMode.CROP -> stop.ratio / (baseRatio[stop.cameraId] ?: 1f)
                else -> 1f
            }
            LensChip(stop.label, stop.ratio, id, zoom)
        }
        return if (chips.any { it.ratio == 1f }) chips else emptyList()
    }

    companion object {
        private const val ACTIVE_TOLERANCE = 0.05f

        fun from(profile: DeviceProfile) =
            LensPlan(profile.zoomStops.sortedBy { it.ratio }, profile.cameras)

        /** The chip to highlight for the open camera and its current zoom ratio. */
        fun activeChip(chips: List<LensChip>, cameraId: String, zoomRatio: Float): LensChip? {
            val own = chips.filter { it.cameraId == cameraId }
            return own.lastOrNull { it.zoomRatio <= zoomRatio + ACTIVE_TOLERANCE } ?: own.firstOrNull()
        }
    }
}
