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

    /** Roles that can become zoom stops; macro, depth and mono never do. */
    val STOP_ROLES = setOf(LensRole.MAIN, LensRole.ULTRA, LensRole.TELE)

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
        if (entries.any { it.hidden && it.facing == Facing.BACK && it.role in STOP_ROLES }) {
            quirks += Quirk.HIDDEN_PHYSICAL_IDS
        }
        val logical = logicalZoomCamera(entries, quirks)
        if (logical != null) quirks += Quirk.USE_LOGICAL_ZOOM
        return entries to quirks
    }

    /**
     * Stops: one per canonical back main/ultra/tele sensor, then crop stops on main for
     * published teles the scan could not find (or 2x when there is no tele at all).
     * A non-empty [catalog] limits stops to the published roles and supplies the labels.
     * With [Quirk.USE_LOGICAL_ZOOM] every stop is a zoom ratio on the logical camera.
     */
    fun zoomStops(
        cameras: List<CameraEntry>,
        quirks: Set<Quirk> = emptySet(),
        catalog: List<CatalogLens> = emptyList(),
    ): List<ZoomStop> {
        val back = cameras.filter { it.facing == Facing.BACK && it.duplicateOf == null }
        val main = back.firstOrNull { it.role == LensRole.MAIN } ?: return emptyList()
        val published = catalog.map { it.role }.toSet()
        val mainLabel = catalog.firstOrNull { it.role == LensRole.MAIN }?.ratio ?: 1f
        val physical = back
            .filter { it.role in STOP_ROLES && (catalog.isEmpty() || it.role in published || it == main) }
            .map { cam ->
                val ratio = if (cam == main) 1f else roundRatio(cam.equivFocalMm / main.equivFocalMm)
                val lens = catalog.filter { it.role == cam.role }
                    .minByOrNull { kotlin.math.abs((it.ratio ?: ratio * mainLabel) - ratio * mainLabel) }
                val label = lens?.takeIf { it.official }?.ratio ?: (ratio * mainLabel)
                ZoomStop(ratio, cam.id, StopMode.PHYSICAL, label(label))
            }
            .distinctBy { it.ratio }
        val teleFound = physical.any { s -> back.first { it.id == s.cameraId }.role == LensRole.TELE }
        val publishedTele = catalog.filter { it.role == LensRole.TELE }.mapNotNull { it.ratio }
        val crops = when {
            teleFound -> emptyList()
            publishedTele.isNotEmpty() -> publishedTele.map { it / mainLabel to it }
            else -> listOf(CROP_STOP_RATIO to CROP_STOP_RATIO * mainLabel)
        }.map { (ratio, shown) -> ZoomStop(ratio, main.id, StopMode.CROP, label(shown)) }
        val stops = (physical + crops).distinctBy { it.ratio }.sortedBy { it.ratio }

        val logical = logicalZoomCamera(cameras, quirks) ?: return stops
        return stops.map { it.copy(cameraId = logical.id, mode = StopMode.LOGICAL) }
    }

    /**
     * The logical camera to zoom through: forced by [Quirk.USE_LOGICAL_ZOOM], or found when
     * a listed logical camera contains the main and another stop lens that is itself hidden.
     */
    fun logicalZoomCamera(cameras: List<CameraEntry>, quirks: Set<Quirk>): CameraEntry? {
        val byId = cameras.associateBy { it.id }
        fun canonical(id: String) = byId[id]?.let { c -> c.duplicateOf?.let(byId::get) ?: c }
        val logicals = cameras.filter { it.role == LensRole.LOGICAL && it.facing == Facing.BACK }
        if (Quirk.USE_LOGICAL_ZOOM in quirks) {
            return logicals.minByOrNull { if (it.hidden) 1 else 0 }
        }
        return logicals.firstOrNull { logical ->
            val members = logical.physicalIds.mapNotNull(::canonical)
            !logical.hidden && members.any { it.role == LensRole.MAIN } &&
                members.any { it.role == LensRole.ULTRA || it.role == LensRole.TELE } &&
                members.all { it.hidden }
        }
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