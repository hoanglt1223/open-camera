/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.skin

/**
 * Design tokens of a camera skin, free of any UI toolkit so other screens (editor, retouch)
 * can read them too: colors are ARGB ints, sizes dp, text sp, durations ms.
 */
data class SkinTokens(
    val id: String,
    val colors: Colors,
    val radii: Radii,
    val type: Typography,
    val motion: Motion,
    val shape: Shape,
) {
    data class Colors(
        val accent: Int,
        /** Optional second stop: the accent is a gradient from [accent] to it. */
        val accentEnd: Int?,
        val foreground: Int,
        val muted: Int,
        /** Capsules and panels drawn over the preview. */
        val surface: Int,
        /** Sheets (More, Settings) when no blurred backdrop is drawn. */
        val sheet: Int,
        val chip: Int,
        val stroke: Int,
        /** Label color of the selected mode. */
        val modeOn: Int,
        val switchOn: Int,
        val record: Int,
    )

    data class Radii(val small: Float, val medium: Float, val large: Float)

    data class Typography(
        val modeSize: Float,
        val modeWeight: Int,
        val labelSize: Float,
        val titleSize: Float,
    )

    /** Cubic bezier control points of the standard easing, as in CSS. */
    data class Motion(
        val fastMs: Int,
        val mediumMs: Int,
        val slowMs: Int,
        val easing: FloatArray,
        /** Lens chips, mode pill and thumbnail use a spring instead of [easing]. */
        val springy: Boolean,
    ) {
        override fun equals(other: Any?) = other is Motion && fastMs == other.fastMs &&
                mediumMs == other.mediumMs && slowMs == other.slowMs &&
                easing.contentEquals(other.easing) && springy == other.springy

        override fun hashCode() = easing.contentHashCode() * 31 + mediumMs
    }

    data class Shape(
        val shutter: Float,
        val shutterRing: Float,
        val previewTop: Float,
        val previewSide: Float,
        val previewRadius: Float,
        /** Top bar as one capsule (Glass) or loose icons (Origin). */
        val topBarCapsule: Boolean,
        /** Glass draws translucent surfaces and may blur sheet backdrops. */
        val glass: Boolean,
    )

    /** Rejects tokens that would hide controls or make them too small to hit. */
    fun validate(): List<String> = buildList {
        fun opaque(name: String, c: Int) { if ((c ushr 24) == 0) add("$name is fully transparent") }
        opaque("accent", colors.accent)
        opaque("foreground", colors.foreground)
        opaque("muted", colors.muted)
        opaque("sheet", colors.sheet)
        opaque("record", colors.record)
        if (shape.shutter < MIN_TOUCH_DP) add("shutter < $MIN_TOUCH_DP dp")
        if (radii.small < 0 || radii.medium < 0 || radii.large < 0) add("negative radius")
        if (shape.previewTop < 0 || shape.previewSide < 0) add("negative preview inset")
        if (type.modeSize < 8f || type.labelSize < 8f) add("text too small")
        listOf(motion.fastMs, motion.mediumMs, motion.slowMs).forEach {
            if (it !in 0..MAX_DURATION_MS) add("duration $it out of range")
        }
        if (motion.easing.size != 4) add("easing needs 4 control points")
    }

    /** Durations scaled for "Remove animations" (scale 0) and the system animator scale. */
    fun scaledMotion(animatorScale: Float): Motion {
        val s = animatorScale.coerceIn(0f, 10f)
        return motion.copy(
            fastMs = (motion.fastMs * s).toInt(),
            mediumMs = (motion.mediumMs * s).toInt(),
            slowMs = (motion.slowMs * s).toInt(),
        )
    }

    companion object {
        const val MIN_TOUCH_DP = 48f
        const val MAX_DURATION_MS = 2000
    }
}

object Skins {
    const val GLASS_ID = "glass"
    const val ORIGIN_ID = "origin"

    /** iOS-26-like: full-bleed feel, translucent capsules, yellow accent. */
    val GLASS = SkinTokens(
        id = GLASS_ID,
        colors = SkinTokens.Colors(
            accent = 0xFFFFD60A.toInt(),
            accentEnd = null,
            foreground = 0xFFFFFFFF.toInt(),
            muted = 0x9EFFFFFF.toInt(),
            surface = 0x6B28282C,
            sheet = 0xEB1E1E22.toInt(),
            chip = 0x24FFFFFF,
            stroke = 0x38FFFFFF,
            modeOn = 0xFFFFD60A.toInt(),
            switchOn = 0xFF34C759.toInt(),
            record = 0xFFFF3B30.toInt(),
        ),
        radii = SkinTokens.Radii(10f, 16f, 28f),
        type = SkinTokens.Typography(modeSize = 14f, modeWeight = 600, labelSize = 12f, titleSize = 17f),
        motion = SkinTokens.Motion(120, 280, 380, floatArrayOf(.25f, .9f, .3f, 1.05f), springy = false),
        shape = SkinTokens.Shape(
            shutter = 78f, shutterRing = 3f, previewTop = 0f, previewSide = 0f, previewRadius = 0f,
            topBarCapsule = true, glass = true,
        ),
    )

    /** vivo/iQOO-like: solid dark surfaces, orange gradient pill, springy motion. */
    val ORIGIN = SkinTokens(
        id = ORIGIN_ID,
        colors = SkinTokens.Colors(
            accent = 0xFFFF7A45.toInt(),
            accentEnd = 0xFFFFB547.toInt(),
            foreground = 0xFFFFFFFF.toInt(),
            muted = 0x9EFFFFFF.toInt(),
            surface = 0xCC121216.toInt(),
            sheet = 0xFF15151A.toInt(),
            chip = 0xFF24242B.toInt(),
            stroke = 0x24FFFFFF,
            modeOn = 0xFF111111.toInt(),
            switchOn = 0xFFFFB547.toInt(),
            record = 0xFFFF3D60.toInt(),
        ),
        radii = SkinTokens.Radii(12f, 20f, 28f),
        type = SkinTokens.Typography(modeSize = 15f, modeWeight = 700, labelSize = 12f, titleSize = 17f),
        motion = SkinTokens.Motion(120, 260, 380, floatArrayOf(.22f, 1f, .36f, 1f), springy = true),
        shape = SkinTokens.Shape(
            shutter = 76f, shutterRing = 4f, previewTop = 72f, previewSide = 8f, previewRadius = 20f,
            topBarCapsule = false, glass = false,
        ),
    )

    val DEFAULT = GLASS
    val ALL = listOf(GLASS, ORIGIN)

    /** Unknown, empty or null ids, and tokens that fail [SkinTokens.validate], give [DEFAULT]. */
    fun byId(id: String?): SkinTokens =
        ALL.firstOrNull { it.id == id?.trim()?.lowercase() }?.takeIf { it.validate().isEmpty() } ?: DEFAULT

    /** The skin to fall back to when [id] failed at runtime. */
    fun other(id: String): SkinTokens = ALL.firstOrNull { it.id != id } ?: DEFAULT
}
