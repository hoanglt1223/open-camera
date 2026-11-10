/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Turns raw Camera2 characteristics into classified lenses and zoom stops.
 *
 * Rules: auxiliary sensors (low resolution or not BACKWARD_COMPATIBLE) never become
 * zoom stops; ids that expose the same sensor are merged onto the lowest id; the
 * back main is the largest usable sensor; a 2x crop stop on main is added when the
 * device has no telephoto.
 */
object LensCatalog {
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666f
    private const val AUX_MAX_MEGAPIXELS = 3f
    private const val MAIN_RATIO_TOLERANCE = 0.05f
    private const val CROP_STOP_RATIO = 2f

    fun equivalentFocalMm(spec: CameraSpec): Float {
        val focal = spec.focalLengthsMm.minOrNull() ?: return 0f
        val diagonal = hypot(spec.sensorWidthMm, spec.sensorHeightMm)
        return if (diagonal > 0f) focal * FULL_FRAME_DIAGONAL_MM / diagonal else 0f
    }

    fun roundRatio(ratio: Float) = maxOf(0.1f, (ratio * 10f).roundToInt() / 10f)

    fun label(ratio: Float): String {
        val rounded = roundRatio(ratio)
        return when {
            rounded == 1f -> "1x"
            rounded == rounded.roundToInt().toFloat() -> rounded.roundToInt().toString()
            else -> rounded.toString()
        }
    }

    fun build(specs: List<CameraSpec>): Pair<List<CameraEntry>, Set<Quirk>> {
        val sorted = specs.sortedWith(compareBy({ it.id.toIntOrNull() ?: Int.MAX_VALUE }, { it.id }))
        val logicalMembers = sorted.filter { it.isLogical }.flatMap { it.physicalIds }.toSet()

        val canonical = mutableMapOf<String, CameraSpec>()
        val duplicateOf = mutableMapOf<String, String>()
        for (spec in sorted.filterNot { it.isLogical }) {
            val key = sensorKey(spec)
            val first = canonical[key]
            if (first == null) canonical[key] = spec else duplicateOf[spec.id] = first.id
        }

        val auxRole = sorted.associate { it.id to auxiliaryRole(it, logicalMembers) }
        val roles = mutableMapOf<String, LensRole>()
        val quirks = mutableSetOf<Quirk>()

        for (facing in Facing.entries) {
            val usable = canonical.values.filter { it.facing == facing && auxRole[it.id] == null }
            val main = usable.maxWithOrNull(
                compareBy<CameraSpec> { it.sensorAreaMm2 }.thenByDescending { it.id.toIntOrNull() ?: 0 }
            ) ?: continue
            val mainEquiv = equivalentFocalMm(main)
            for (spec in usable) {
                val ratio = equivalentFocalMm(spec) / mainEquiv
                roles[spec.id] = when {
                    spec == main -> if (facing == Facing.FRONT) LensRole.FRONT else LensRole.MAIN
                    facing == Facing.FRONT -> LensRole.FRONT_ULTRA
                    ratio < 1f - MAIN_RATIO_TOLERANCE -> LensRole.ULTRA
                    ratio > 1f + MAIN_RATIO_TOLERANCE -> LensRole.TELE
                    else -> LensRole.MAIN
                }
            }
        }

        val entries = sorted.map { spec ->
            val dupOf = duplicateOf[spec.id]
            val role = when {
                spec.isLogical -> LensRole.LOGICAL
                dupOf != null -> roles[dupOf] ?: auxRole[dupOf] ?: LensRole.MACRO
                else -> roles[spec.id] ?: auxRole[spec.id] ?: LensRole.MACRO
            }
            if (dupOf != null) {
                quirks += if (role == LensRole.ULTRA) Quirk.ULTRAWIDE_DUP_ID else Quirk.DUPLICATE_CAMERA_ID
            }
            CameraEntry(
                id = spec.id,
                facing = spec.facing,
                role = role,
                focalMm = spec.focalLengthsMm.minOrNull() ?: 0f,
                equivFocalMm = (equivalentFocalMm(spec) * 10f).roundToInt() / 10f,
                sensorWidthMm = spec.sensorWidthMm,
                sensorHeightMm = spec.sensorHeightMm,
                pixelWidth = spec.pixelWidth,
                pixelHeight = spec.pixelHeight,
                physicalIds = spec.physicalIds,
                duplicateOf = dupOf,
                hidden = spec.hidden,
            )
        }
        return entries to quirks
    }

    fun zoomStops(cameras: List<CameraEntry>): List<ZoomStop> {
        val back = cameras.filter { it.facing == Facing.BACK && it.duplicateOf == null }
        val main = back.firstOrNull { it.role == LensRole.MAIN } ?: return emptyList()
        val stops = back
            .filter { it.role == LensRole.MAIN || it.role == LensRole.ULTRA || it.role == LensRole.TELE }
            .map {
                val ratio = if (it == main) 1f else roundRatio(it.equivFocalMm / main.equivFocalMm)
                ZoomStop(ratio, it.id, StopMode.PHYSICAL, label(ratio))
            }
            .distinctBy { it.ratio }
            .toMutableList()
        if (back.none { it.role == LensRole.TELE }) {
            stops += ZoomStop(CROP_STOP_RATIO, main.id, StopMode.CROP, label(CROP_STOP_RATIO))
        }
        return stops.sortedBy { it.ratio }
    }

    private fun auxiliaryRole(spec: CameraSpec, logicalMembers: Set<String>): LensRole? = when {
        spec.isLogical -> null
        Capability.MONOCHROME in spec.capabilities -> LensRole.MONO
        Capability.DEPTH_OUTPUT in spec.capabilities -> LensRole.DEPTH
        Capability.BACKWARD_COMPATIBLE !in spec.capabilities ||
            spec.megapixels <= AUX_MAX_MEGAPIXELS ->
            // A low resolution sensor bundled into a logical camera serves depth; otherwise macro.
            if (spec.id in logicalMembers) LensRole.DEPTH else LensRole.MACRO
        else -> null
    }

    private fun sensorKey(spec: CameraSpec) = listOf(
        spec.facing.key,
        ((spec.focalLengthsMm.minOrNull() ?: 0f) * 1000).roundToInt(),
        (spec.sensorWidthMm * 100).roundToInt(),
        (spec.sensorHeightMm * 100).roundToInt(),
        spec.pixelWidth,
        spec.pixelHeight,
    ).joinToString("|")
}