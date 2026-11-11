/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.model

/** Groups double as copy/paste and preset subsets, and as the before/after toggles. */
enum class Group { LIGHT, COLOR, CURVE, HSL, GRADING, DETAIL, EFFECTS, LOOK, GEOMETRY }

/** One slider. Values are stored in slider units (as the user sees them). */
data class Param(
    val key: String,
    val group: Group,
    val min: Float,
    val max: Float,
    val default: Float = 0f,
    val step: Float = 1f,
)

/**
 * Every global parameter of the develop pipeline. Keys are part of the stored JSON
 * ([EditStack]) and the preset format, so they never change; add new keys instead.
 */
object Params {
    const val EXPOSURE = "exposure"
    const val CONTRAST = "contrast"
    const val HIGHLIGHTS = "highlights"
    const val SHADOWS = "shadows"
    const val WHITES = "whites"
    const val BLACKS = "blacks"
    const val TEMP = "temp"
    const val TINT = "tint"
    const val VIBRANCE = "vibrance"
    const val SATURATION = "saturation"
    const val CLARITY = "clarity"
    const val TEXTURE = "texture"
    const val DEHAZE = "dehaze"
    const val SHARPEN = "sharpen.amount"
    const val SHARPEN_RADIUS = "sharpen.radius"
    const val SHARPEN_DETAIL = "sharpen.detail"
    const val SHARPEN_MASKING = "sharpen.masking"
    const val NR_LUMA = "nr.luma"
    const val NR_COLOR = "nr.color"
    const val VIGNETTE = "vignette.amount"
    const val VIGNETTE_MIDPOINT = "vignette.midpoint"
    const val VIGNETTE_ROUNDNESS = "vignette.roundness"
    const val VIGNETTE_FEATHER = "vignette.feather"
    const val VIGNETTE_HIGHLIGHTS = "vignette.highlights"
    const val GRAIN = "grain.amount"
    const val GRAIN_SIZE = "grain.size"
    const val GRAIN_ROUGHNESS = "grain.roughness"
    const val LOOK_INTENSITY = "look.intensity"
    const val PC_HIGHLIGHTS = "pc.highlights"
    const val PC_LIGHTS = "pc.lights"
    const val PC_DARKS = "pc.darks"
    const val PC_SHADOWS = "pc.shadows"
    const val GRADE_BLENDING = "grade.blending"
    const val GRADE_BALANCE = "grade.balance"
    const val CROP_LEFT = "crop.left"
    const val CROP_TOP = "crop.top"
    const val CROP_RIGHT = "crop.right"
    const val CROP_BOTTOM = "crop.bottom"
    const val CROP_ANGLE = "crop.angle"
    const val CROP_ROT90 = "crop.rot90"
    const val CROP_FLIP_H = "crop.flipH"
    const val CROP_FLIP_V = "crop.flipV"
    const val CROP_ASPECT = "crop.aspect"
    const val PERSPECTIVE_V = "persp.v"
    const val PERSPECTIVE_H = "persp.h"

    val HSL_BANDS = listOf("red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta")

    /** Band centres in degrees, matching [HSL_BANDS]. */
    val HSL_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 225f, 275f, 315f)

    val GRADE_RANGES = listOf("shadows", "midtones", "highlights", "global")

    fun hsl(band: String, what: String) = "hsl.$band.$what"
    fun grade(range: String, what: String) = "grade.$range.$what"

    val ALL: List<Param> = buildList {
        add(Param(EXPOSURE, Group.LIGHT, -5f, 5f, step = 0.01f))
        for (k in listOf(CONTRAST, HIGHLIGHTS, SHADOWS, WHITES, BLACKS)) add(Param(k, Group.LIGHT, -100f, 100f))
        for (k in listOf(TEMP, TINT, VIBRANCE, SATURATION)) add(Param(k, Group.COLOR, -100f, 100f))
        for (k in listOf(PC_HIGHLIGHTS, PC_LIGHTS, PC_DARKS, PC_SHADOWS)) add(Param(k, Group.CURVE, -100f, 100f))
        for (b in HSL_BANDS) for (w in listOf("hue", "sat", "lum")) add(Param(hsl(b, w), Group.HSL, -100f, 100f))
        for (r in GRADE_RANGES) {
            add(Param(grade(r, "hue"), Group.GRADING, 0f, 360f))
            add(Param(grade(r, "sat"), Group.GRADING, 0f, 100f))
            add(Param(grade(r, "lum"), Group.GRADING, -100f, 100f))
        }
        add(Param(GRADE_BLENDING, Group.GRADING, 0f, 100f, 50f))
        add(Param(GRADE_BALANCE, Group.GRADING, -100f, 100f))
        for (k in listOf(CLARITY, TEXTURE, DEHAZE)) add(Param(k, Group.DETAIL, -100f, 100f))
        add(Param(SHARPEN, Group.DETAIL, 0f, 150f))
        add(Param(SHARPEN_RADIUS, Group.DETAIL, 0.5f, 3f, 1f, 0.1f))
        add(Param(SHARPEN_DETAIL, Group.DETAIL, 0f, 100f, 25f))
        add(Param(SHARPEN_MASKING, Group.DETAIL, 0f, 100f))
        add(Param(NR_LUMA, Group.DETAIL, 0f, 100f))
        add(Param(NR_COLOR, Group.DETAIL, 0f, 100f))
        add(Param(VIGNETTE, Group.EFFECTS, -100f, 100f))
        add(Param(VIGNETTE_MIDPOINT, Group.EFFECTS, 0f, 100f, 50f))
        add(Param(VIGNETTE_ROUNDNESS, Group.EFFECTS, -100f, 100f))
        add(Param(VIGNETTE_FEATHER, Group.EFFECTS, 0f, 100f, 50f))
        add(Param(VIGNETTE_HIGHLIGHTS, Group.EFFECTS, 0f, 100f))
        add(Param(GRAIN, Group.EFFECTS, 0f, 100f))
        add(Param(GRAIN_SIZE, Group.EFFECTS, 0f, 100f, 25f))
        add(Param(GRAIN_ROUGHNESS, Group.EFFECTS, 0f, 100f, 50f))
        add(Param(LOOK_INTENSITY, Group.LOOK, 0f, 100f, 100f))
        add(Param(CROP_LEFT, Group.GEOMETRY, 0f, 1f, 0f, 0.001f))
        add(Param(CROP_TOP, Group.GEOMETRY, 0f, 1f, 0f, 0.001f))
        add(Param(CROP_RIGHT, Group.GEOMETRY, 0f, 1f, 1f, 0.001f))
        add(Param(CROP_BOTTOM, Group.GEOMETRY, 0f, 1f, 1f, 0.001f))
        add(Param(CROP_ANGLE, Group.GEOMETRY, -45f, 45f, 0f, 0.1f))
        add(Param(CROP_ROT90, Group.GEOMETRY, 0f, 3f))
        add(Param(CROP_FLIP_H, Group.GEOMETRY, 0f, 1f))
        add(Param(CROP_FLIP_V, Group.GEOMETRY, 0f, 1f))
        // 0 = free, -1 = original, otherwise width / height.
        add(Param(CROP_ASPECT, Group.GEOMETRY, -1f, 10f, 0f, 0.0001f))
        add(Param(PERSPECTIVE_V, Group.GEOMETRY, -100f, 100f))
        add(Param(PERSPECTIVE_H, Group.GEOMETRY, -100f, 100f))
    }

    private val byKey = ALL.associateBy { it.key }

    operator fun get(key: String): Param? = byKey[key]

    fun default(key: String): Float = byKey[key]?.default ?: 0f

    fun clamp(key: String, value: Float): Float {
        val p = byKey[key] ?: return value
        return if (value.isFinite()) value.coerceIn(p.min, p.max) else p.default
    }

    fun inGroup(group: Group) = ALL.filter { it.group == group }

    /** Parameters a local mask can change; values are deltas in the same units as the global slider. */
    val LOCAL = listOf(EXPOSURE, CONTRAST, HIGHLIGHTS, SHADOWS, TEMP, TINT, SATURATION, CLARITY, SHARPEN, NR_LUMA, DEHAZE)
}
