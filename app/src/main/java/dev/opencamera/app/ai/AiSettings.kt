/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.content.Context
import androidx.preference.PreferenceManager

/** ML feature switches. Everything except scene detection is off by default. */
class AiSettings(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    var portrait: Boolean
        get() = prefs.getBoolean(KEY_PORTRAIT, false)
        set(value) = prefs.edit().putBoolean(KEY_PORTRAIT, value).apply()

    /** Slider position in [0, 1]; 0.93 is about f/2. */
    val portraitAperture get() = prefs.getInt(KEY_PORTRAIT_APERTURE, 93) / 100f
    val livePortraitPreview get() = prefs.getBoolean(KEY_LIVE_PORTRAIT, true)
    val sceneDetection get() = prefs.getBoolean(KEY_SCENE, true)
    val composition get() = prefs.getBoolean(KEY_COMPOSITION, false)
    val bestShot get() = prefs.getBoolean(KEY_BEST_SHOT, false)
    val bestShotFrames get() = prefs.getString(KEY_BEST_SHOT_FRAMES, "5")?.toIntOrNull()?.coerceIn(2, 8) ?: 5
    /** 0 = off. */
    val beauty get() = prefs.getInt(KEY_BEAUTY, 0) / 100f

    companion object {
        const val KEY_PORTRAIT = "ai_portrait"
        const val KEY_PORTRAIT_APERTURE = "ai_portrait_aperture"
        const val KEY_LIVE_PORTRAIT = "ai_live_portrait"
        const val KEY_SCENE = "ai_scene_detection"
        const val KEY_COMPOSITION = "ai_composition"
        const val KEY_BEST_SHOT = "ai_best_shot"
        const val KEY_BEST_SHOT_FRAMES = "ai_best_shot_frames"
        const val KEY_BEAUTY = "ai_beauty"
    }
}
