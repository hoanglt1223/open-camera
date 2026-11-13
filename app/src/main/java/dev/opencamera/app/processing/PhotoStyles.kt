/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.content.SharedPreferences
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.render.ColorMath
import dev.opencamera.app.develop.render.DevelopKernel
import dev.opencamera.app.develop.render.DevelopUniforms
import dev.opencamera.pipeline.looks.LookDescriptor
import dev.opencamera.pipeline.looks.Lut3d
import kotlin.math.min
import kotlin.math.roundToInt

/** Tone, warmth and colour, each -100..100. */
data class PhotoStyle(val tone: Int = 0, val warmth: Int = 0, val color: Int = 0) {
    fun clamped() = PhotoStyle(tone.coerceIn(-100, 100), warmth.coerceIn(-100, 100), color.coerceIn(-100, 100))
}

/**
 * Adjustable photographic styles. A style is a look whose LUT is computed from the editor's own
 * pixel kernel and the matching edit parameters, so the viewfinder, the saved photo and the
 * editor preset made from it render the same colours. The selected style is the look id
 * [LOOK_ID]; a photo in the processing queue carries its values in the id ([frozenId]), so moving
 * a slider after the shutter does not change photos already taken.
 */
object PhotoStyles {
    const val LOOK_ID = "style"
    private const val FROZEN = "style:"
    private const val LUT_SIZE = 17

    const val KEY_TONE = "photo_style_tone"
    const val KEY_WARMTH = "photo_style_warmth"
    const val KEY_COLOR = "photo_style_color"

    /** Built-in starting points; the user fine-tunes from any of them. */
    val PRESETS = listOf(
        "standard" to PhotoStyle(),
        "rich" to PhotoStyle(tone = 45, warmth = 5, color = 10),
        "vibrant" to PhotoStyle(tone = 10, color = 45),
        "warm" to PhotoStyle(warmth = 40, color = 10),
        "cool" to PhotoStyle(warmth = -40),
        "muted" to PhotoStyle(tone = -30, color = -35),
    )

    fun isStyle(id: String?) = id == LOOK_ID || id?.startsWith(FROZEN) == true

    fun frozenId(s: PhotoStyle) = "$FROZEN${s.tone}:${s.warmth}:${s.color}"

    /** Values of a frozen id, or null when [id] is not one (or is malformed). */
    fun parseFrozen(id: String): PhotoStyle? {
        if (!id.startsWith(FROZEN)) return null
        val v = id.removePrefix(FROZEN).split(':').mapNotNull { it.toIntOrNull() }
        return if (v.size == 3) PhotoStyle(v[0], v[1], v[2]).clamped() else null
    }

    fun load(prefs: SharedPreferences) = PhotoStyle(
        prefs.getInt(KEY_TONE, 0), prefs.getInt(KEY_WARMTH, 0), prefs.getInt(KEY_COLOR, 0),
    ).clamped()

    fun save(prefs: SharedPreferences, s: PhotoStyle) {
        val c = s.clamped()
        prefs.edit().putInt(KEY_TONE, c.tone).putInt(KEY_WARMTH, c.warmth).putInt(KEY_COLOR, c.color).apply()
    }

    fun descriptor(id: String, name: String) = LookDescriptor(id, name, "", "", intensity = 1f)

    /** The editor parameters a style stands for; also what "save as preset" writes. */
    fun toEditStack(s: PhotoStyle): EditStack {
        val c = s.clamped()
        return EditStack()
            .with(Params.CONTRAST, c.tone * 0.6f)
            .with(Params.SHADOWS, -c.tone * 0.3f)
            .with(Params.TEMP, c.warmth * 0.3f)
            .with(Params.VIBRANCE, c.color * 0.6f)
            .with(Params.SATURATION, c.color * 0.2f)
            .let { st -> st.copy(values = st.values.filterValues { it != 0f }) }
    }

    val PRESET_GROUPS = setOf(Group.LIGHT, Group.COLOR)

    /**
     * The style as a LUT over display-referred (sRGB) colours: each grid colour runs through
     * [DevelopKernel] as a flat patch (no local detail), exactly as the editor shades it.
     */
    fun lut(s: PhotoStyle, size: Int = LUT_SIZE): Lut3d {
        val u = DevelopUniforms.from(toEditStack(s), null)
        val px = DevelopKernel.Pixel()
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            px.lin[0] = ColorMath.srgbToLinear(r / (size - 1f))
            px.lin[1] = ColorMath.srgbToLinear(g / (size - 1f))
            px.lin[2] = ColorMath.srgbToLinear(b / (size - 1f))
            for (k in 0..2) {
                px.bs[k] = px.lin[k]
                px.bm[k] = px.lin[k]
                px.bl[k] = px.lin[k]
            }
            px.bl[3] = min(px.lin[0], min(px.lin[1], px.lin[2]))
            DevelopKernel.shade(u, px, 0.5f, 0.5f, 1f, 1f)
            for (k in 0..2) {
                val v = px.out[k]
                data[i++] = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
            }
        }
        return Lut3d(size, data)
    }

    /** Index of the built-in preset with exactly these values, or -1 for a custom style. */
    fun presetIndex(s: PhotoStyle) = PRESETS.indexOfFirst { it.second == s }

    fun label(v: Int) = if (v > 0) "+$v" else v.toString()

    fun fromSlider(v: Float) = v.roundToInt().coerceIn(-100, 100)
}
