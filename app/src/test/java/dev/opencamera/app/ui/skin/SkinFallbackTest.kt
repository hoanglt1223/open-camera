/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinFallbackTest {
    private class MemoryStore : SkinStore {
        val values = HashMap<String, Any?>()
        override fun getString(key: String) = values[key] as? String
        override fun putString(key: String, value: String?) { values[key] = value }
        override fun getInt(key: String) = values[key] as? Int ?: 0
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    }

    @Test
    fun builtInSkinsAreValid() {
        Skins.ALL.forEach { assertTrue(it.id, it.validate().isEmpty()) }
        assertEquals(Skins.GLASS_ID, Skins.DEFAULT.id)
    }

    @Test
    fun unknownEmptyOrNullIdGivesDefault() {
        assertEquals(Skins.DEFAULT, Skins.byId(null))
        assertEquals(Skins.DEFAULT, Skins.byId(""))
        assertEquals(Skins.DEFAULT, Skins.byId("classic"))
        assertEquals(Skins.ORIGIN, Skins.byId(" Origin "))
    }

    @Test
    fun validateRejectsBrokenTokens() {
        val transparent = Skins.GLASS.copy(colors = Skins.GLASS.colors.copy(foreground = 0x00FFFFFF))
        val tiny = Skins.GLASS.copy(shape = Skins.GLASS.shape.copy(shutter = 30f))
        val slow = Skins.GLASS.copy(motion = Skins.GLASS.motion.copy(slowMs = 60_000))
        listOf(transparent, tiny, slow).forEach { assertTrue(it.validate().isNotEmpty()) }
    }

    @Test
    fun removeAnimationsScalesDurationsToZero() {
        val m = Skins.ORIGIN.scaledMotion(0f)
        assertEquals(0, m.fastMs)
        assertEquals(0, m.mediumMs)
        assertEquals(0, m.slowMs)
    }

    @Test
    fun cleanLaunchComposesSelectedSkin() {
        val guard = SkinCrashGuard(MemoryStore())
        assertEquals(SkinCrashGuard.Decision.Compose("origin", null), guard.onLaunch("origin"))
    }

    @Test
    fun crashBeforeFirstFrameSwitchesToOtherSkinThenToLegacy() {
        val store = MemoryStore()
        val guard = SkinCrashGuard(store)

        guard.beforeContent("glass") // process dies before the first frame
        assertEquals(SkinCrashGuard.Decision.Compose("origin", "glass"), guard.onLaunch("glass"))
        assertEquals("origin", store.getString(SkinRepository.KEY_SKIN))

        guard.beforeContent("origin") // dies again
        assertEquals(SkinCrashGuard.Decision.Legacy, guard.onLaunch("origin"))
        assertEquals(SkinCrashGuard.Decision.Legacy, guard.onLaunch("origin"))

        guard.clear() // the user picks a skin by hand
        assertEquals(SkinCrashGuard.Decision.Compose("origin", null), guard.onLaunch("origin"))
    }

    @Test
    fun firstFrameClearsTheFlag() {
        val guard = SkinCrashGuard(MemoryStore())
        guard.beforeContent("glass")
        guard.onFirstFrame()
        assertEquals(SkinCrashGuard.Decision.Compose("glass", null), guard.onLaunch("glass"))
    }

    @Test
    fun runtimeFailureTriesOtherSkinOnceThenGivesUp() {
        val store = MemoryStore()
        val guard = SkinCrashGuard(store)
        assertEquals("origin", guard.onRuntimeFailure("glass", setOf("glass")))
        assertNull(guard.onRuntimeFailure("origin", setOf("glass", "origin")))
        assertEquals(SkinCrashGuard.Decision.Legacy, guard.onLaunch("origin"))
    }

    @Test
    fun blurPolicySteps() {
        assertEquals(BlurPolicy.NONE, BlurPolicy.choose(false, 36, false, 34, false))
        assertEquals(BlurPolicy.FULL, BlurPolicy.choose(true, 36, false, 34, false))
        // gauguin: Android 17 but no media performance class.
        assertEquals(BlurPolicy.STATIC, BlurPolicy.choose(true, 37, false, 0, false))
        assertEquals(BlurPolicy.STATIC, BlurPolicy.choose(true, 30, false, 0, false))
        assertEquals(BlurPolicy.STATIC, BlurPolicy.choose(true, 36, false, 34, true))
        assertEquals(BlurPolicy.NONE, BlurPolicy.choose(true, 30, true, 0, false))
        assertEquals(BlurPolicy.NONE, BlurPolicy.choose(true, 36, true, 34, true))
    }
}
