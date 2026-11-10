/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dev.opencamera.pipeline.looks.CubeParser
import dev.opencamera.pipeline.looks.LookDescriptor
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.Lut3d
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import org.json.JSONObject

/** Built-in looks from `assets/looks`. LUTs are parsed on first use and cached. */
class LooksRepository private constructor(context: Context) {
    private val assets = context.applicationContext.assets
    private val luts = HashMap<String, Lut3d>()

    val descriptors: List<LookDescriptor> by lazy {
        val index = JSONObject(read("$ROOT/index.json")).getJSONArray("looks")
        List(index.length()) { LookDescriptor.parse(read("$ROOT/${index.getString(it)}/look.json")) }
    }

    fun descriptor(id: String?) = descriptors.firstOrNull { it.id == id }

    /** Render parameters for [id], or null for "no look". Blocking; call off the main thread. */
    fun params(id: String?, intensity: Float): LookParams? {
        val d = descriptor(id) ?: return null
        return LookParams.of(d, lut(d), intensity)
    }

    @Synchronized
    private fun lut(d: LookDescriptor): Lut3d = luts.getOrPut(d.id) {
        CubeParser.parse(read("$ROOT/${d.id}/${d.lutFile}"))
    }

    private fun read(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val ROOT = "looks"

        @Volatile
        private var instance: LooksRepository? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: LooksRepository(context).also { instance = it }
        }
    }
}

/** Look and frame settings, stored in the default preferences. */
class LookPreferences(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    var lookId: String
        get() = prefs.getString(KEY_LOOK, NONE) ?: NONE
        set(value) = prefs.edit().putString(KEY_LOOK, value).apply()

    val intensity: Float get() = prefs.getInt(KEY_INTENSITY, 100).coerceIn(0, 100) / 100f

    val watermark: WatermarkTemplate get() = WatermarkTemplate.fromKey(prefs.getString(KEY_WATERMARK, null))

    companion object {
        const val NONE = "none"
        const val KEY_LOOK = "look_id"
        const val KEY_INTENSITY = "look_intensity"
        const val KEY_WATERMARK = "watermark_template"
    }
}
