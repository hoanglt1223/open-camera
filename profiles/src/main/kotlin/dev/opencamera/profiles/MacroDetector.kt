/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

/**
 * Finds the back macro camera. A curated profile naming a `macro` camera wins; otherwise the
 * scanned characteristics decide: a small colour sensor that focuses very close, or a
 * fixed-focus one outside any logical camera (fixed-focus members of a logical camera serve
 * depth). Mono/NIR sensors and logical cameras never qualify, also not through a profile.
 */
object MacroDetector {
    /** 5 cm. Main cameras stop around 10 cm (10 diopters). */
    const val MACRO_MIN_DIOPTERS = 20f
    const val MAX_MEGAPIXELS = 5f
    const val MAX_AREA_OF_MAIN = 0.35f

    fun pick(profile: DeviceProfile?, specs: List<CameraSpec>): String? {
        val byId = specs.associateBy { it.id }
        val logicalMembers = specs.filter { it.isLogical }.flatMap { it.physicalIds }.toSet()
        if (profile != null && profile.source != ProfileSource.AUTO) {
            profile.cameras.firstOrNull { entry ->
                entry.role == LensRole.MACRO && entry.facing == Facing.BACK &&
                    byId[entry.id]?.let(::colourCapable) == true
            }?.let { return it.id }
        }
        val back = specs.filter { it.facing == Facing.BACK && colourCapable(it) }
        val main = back.maxByOrNull { it.sensorAreaMm2 } ?: return null
        return back.filter { spec ->
            spec != main &&
                spec.megapixels <= MAX_MEGAPIXELS &&
                spec.sensorAreaMm2 <= main.sensorAreaMm2 * MAX_AREA_OF_MAIN &&
                (spec.minFocusDistance >= MACRO_MIN_DIOPTERS ||
                    (spec.minFocusDistance == 0f && spec.id !in logicalMembers))
        }.sortedWith(
            compareByDescending<CameraSpec> { it.minFocusDistance }
                .thenBy { it.id.toIntOrNull() ?: Int.MAX_VALUE },
        ).firstOrNull()?.id
    }

    private fun colourCapable(spec: CameraSpec) =
        !spec.isLogical &&
            !spec.monoColorFilter &&
            Capability.MONOCHROME !in spec.capabilities &&
            Capability.DEPTH_OUTPUT !in spec.capabilities &&
            Capability.BACKWARD_COMPATIBLE in spec.capabilities
}
