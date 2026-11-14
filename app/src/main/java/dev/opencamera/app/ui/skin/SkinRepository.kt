/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.skin

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small key-value view of the preferences, so the fallback rules run in JVM tests. */
interface SkinStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getInt(key: String): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
}

class PrefsSkinStore(private val prefs: SharedPreferences) : SkinStore {
    override fun getString(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()
    override fun putString(key: String, value: String?) = prefs.edit { putString(key, value) }
    override fun getInt(key: String) = runCatching { prefs.getInt(key, 0) }.getOrDefault(0)
    override fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    override fun getBoolean(key: String, default: Boolean) =
        runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)
}

/**
 * Crash bookkeeping for the skin layer. A flag is written before the overlay is composed and
 * cleared after its first frame; a flag still set at the next launch means the skin took the
 * process down. One failure switches to the other skin, a second one keeps the classic View
 * layout until the user picks a skin again.
 */
class SkinCrashGuard(private val store: SkinStore) {
    sealed interface Decision {
        data class Compose(val skinId: String, val resetFrom: String?) : Decision
        data object Legacy : Decision
    }

    /**
     * [lastExitWasCrash] is false when the system says the previous process ended for another
     * reason (permission revoked, killed from recents, low memory): a skin cut short by that is
     * not to blame.
     */
    fun onLaunch(selectedId: String, lastExitWasCrash: Boolean = true): Decision {
        val pending = store.getString(KEY_PENDING)
        var failures = store.getInt(KEY_FAILURES)
        var resetFrom: String? = null
        var skin = selectedId
        if (!pending.isNullOrEmpty() && !lastExitWasCrash) {
            store.putString(KEY_PENDING, null)
        } else if (!pending.isNullOrEmpty()) {
            failures++
            store.putInt(KEY_FAILURES, failures)
            store.putString(KEY_PENDING, null)
            if (failures == 1) {
                skin = Skins.other(pending).id
                store.putString(SkinRepository.KEY_SKIN, skin)
                resetFrom = pending
            }
        }
        return if (failures >= 2) Decision.Legacy else Decision.Compose(skin, resetFrom)
    }

    fun beforeContent(skinId: String) = store.putString(KEY_PENDING, skinId)

    fun onFirstFrame() {
        store.putString(KEY_PENDING, null)
        store.putInt(KEY_FAILURES, 0)
    }

    /**
     * A caught runtime failure of [skinId]: returns the skin to try next in this session, or
     * null when [tried] already holds every skin and the View layout must take over.
     */
    fun onRuntimeFailure(skinId: String, tried: Set<String>): String? {
        store.putString(KEY_PENDING, null)
        val next = Skins.ALL.map { it.id }.firstOrNull { it != skinId && it !in tried }
        if (next != null) store.putString(SkinRepository.KEY_SKIN, next)
        else store.putInt(KEY_FAILURES, 2)
        return next
    }

    /** Picking a skin by hand gives Compose another chance. */
    fun clear() {
        store.putString(KEY_PENDING, null)
        store.putInt(KEY_FAILURES, 0)
    }

    companion object {
        const val KEY_PENDING = "skin_crash_pending"
        const val KEY_FAILURES = "skin_crash_failures"
    }
}

/**
 * The selected skin and blur choice, read from the `ui_skin` / `ui_blur` preferences and
 * updated live when Settings changes them.
 */
class SkinRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    val store = PrefsSkinStore(prefs)
    val crashGuard = SkinCrashGuard(store)

    private val _skin = MutableStateFlow(Skins.byId(store.getString(KEY_SKIN)))
    val skin: StateFlow<SkinTokens> = _skin.asStateFlow()

    private val _blurEnabled = MutableStateFlow(store.getBoolean(KEY_BLUR, true))
    val blurEnabled: StateFlow<Boolean> = _blurEnabled.asStateFlow()

    private val _blurDowngraded = MutableStateFlow(store.getBoolean(KEY_BLUR_DOWNGRADED, false))

    private val _policy = MutableStateFlow(currentPolicy())
    val blurPolicy: StateFlow<BlurPolicy> = _policy.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_SKIN -> _skin.value = Skins.byId(store.getString(KEY_SKIN))
            KEY_BLUR -> _blurEnabled.value = store.getBoolean(KEY_BLUR, true)
            KEY_BLUR_DOWNGRADED -> _blurDowngraded.value = store.getBoolean(KEY_BLUR_DOWNGRADED, false)
        }
        _policy.value = currentPolicy()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun select(id: String) {
        crashGuard.clear()
        store.putString(KEY_SKIN, Skins.byId(id).id)
    }

    fun setBlurEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_BLUR, enabled) }

    fun animatorScale(): Float = runCatching {
        Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    private fun currentPolicy() = BlurPolicy.choose(
        userEnabled = _blurEnabled.value,
        sdk = Build.VERSION.SDK_INT,
        lowRam = runCatching { app.getSystemService(ActivityManager::class.java).isLowRamDevice }.getOrDefault(true),
        performanceClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0,
        downgraded = _blurDowngraded.value,
    )

    companion object {
        const val KEY_SKIN = "ui_skin"
        const val KEY_BLUR = "ui_blur"
        const val KEY_BLUR_DOWNGRADED = "ui_blur_downgraded"
        const val BLUR_BUDGET_MS = 8.0

        @Volatile
        private var instance: SkinRepository? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: SkinRepository(context).also { instance = it }
        }
    }
}

/**
 * How Glass draws sheet backdrops. The live preview is never blurred per frame: FULL blurs
 * one preview snapshot on the GPU (RenderEffect, API 31+), STATIC blurs a tiny snapshot on
 * the CPU once, NONE uses a near-opaque solid surface.
 */
enum class BlurPolicy {
    FULL, STATIC, NONE;

    companion object {
        fun choose(userEnabled: Boolean, sdk: Int, lowRam: Boolean, performanceClass: Int, downgraded: Boolean): BlurPolicy {
            if (!userEnabled) return NONE
            var level = if (sdk >= 31 && performanceClass >= 31) FULL else STATIC
            if (lowRam) level = level.lower()
            if (downgraded) level = level.lower()
            return level
        }

        private fun BlurPolicy.lower() = when (this) {
            FULL -> STATIC
            STATIC, NONE -> NONE
        }
    }
}
