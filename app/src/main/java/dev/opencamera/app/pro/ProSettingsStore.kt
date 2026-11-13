/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Persists [ProSettings] per camera id, plus the global Pro on/off switch.
 */
class ProSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Returns true the first time it is called for [reason]; the aids notice shows once per reason. */
    fun firstAidsNotice(reason: ExposureAids.Unavailable): Boolean {
        val key = "aids_notice_${reason.name}"
        if (prefs.getBoolean(key, false)) return false
        prefs.edit { putBoolean(key, true) }
        return true
    }

    fun load(cameraId: String): ProSettings {
        val json = prefs.getString(key(cameraId), null) ?: return ProSettings()
        return runCatching {
            val o = JSONObject(json)
            ProSettings(
                iso = o.optIntOrNull("iso"),
                exposureNs = if (o.has("exposureNs")) o.getLong("exposureNs") else null,
                wbMode = enumValueOf(o.optString("wbMode", WbMode.AUTO.name)),
                kelvin = o.optInt("kelvin", 5500),
                focusDiopters = if (o.has("focus")) o.getDouble("focus").toFloat() else null,
                evIndex = o.optInt("ev", 0),
                metering = enumValueOf(o.optString("metering", MeteringMode.CENTER.name)),
                aeLock = o.optBoolean("aeLock", false),
                peaking = o.optBoolean("peaking", false),
                zebra = o.optBoolean("zebra", false),
                histogram = o.optBoolean("histogram", true),
                falseColor = o.optBoolean("falseColor", false),
                zebraPercent = o.optInt("zebraPercent", ExposureAids.DEFAULT_ZEBRA_PERCENT).coerceIn(1, 100),
            )
        }.getOrDefault(ProSettings())
    }

    fun save(cameraId: String, settings: ProSettings) {
        val o = JSONObject()
            .put("wbMode", settings.wbMode.name)
            .put("kelvin", settings.kelvin)
            .put("ev", settings.evIndex)
            .put("metering", settings.metering.name)
            .put("aeLock", settings.aeLock)
            .put("peaking", settings.peaking)
            .put("zebra", settings.zebra)
            .put("histogram", settings.histogram)
            .put("falseColor", settings.falseColor)
            .put("zebraPercent", settings.zebraPercent)
        settings.iso?.let { o.put("iso", it) }
        settings.exposureNs?.let { o.put("exposureNs", it) }
        settings.focusDiopters?.let { o.put("focus", it.toDouble()) }
        prefs.edit { putString(key(cameraId), o.toString()) }
    }

    private fun JSONObject.optIntOrNull(name: String) = if (has(name)) getInt(name) else null

    private fun key(cameraId: String) = "camera_$cameraId"

    companion object {
        const val PREFS_NAME = "pro_mode"
        private const val KEY_ENABLED = "enabled"
    }
}
