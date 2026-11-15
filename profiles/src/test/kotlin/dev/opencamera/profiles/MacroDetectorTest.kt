/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacroDetectorTest {
    private val specs = GauguinFixture.specs
    private val auto = ProfileFactory.autoProfile(GauguinFixture.build, specs)
    private val caps = setOf(Capability.BACKWARD_COMPATIBLE)

    private fun verified(macroId: String) = auto.copy(
        source = ProfileSource.VERIFIED,
        cameras = auto.cameras.map { if (it.id == macroId) it.copy(role = LensRole.MACRO) else it },
    )

    @Test
    fun gauguinScanFindsTheFixedFocusMacroNotTheDepthSensor() {
        assertEquals("2", MacroDetector.pick(null, specs))
        assertEquals("2", MacroDetector.pick(auto, specs))
    }

    @Test
    fun monoSensorNeverQualifies() {
        val mono = specs.map { if (it.id == "2") it.copy(monoColorFilter = true) else it }
        assertNull(MacroDetector.pick(null, mono))
        val monoCap = specs.map { if (it.id == "2") it.copy(capabilities = caps + Capability.MONOCHROME) else it }
        assertNull(MacroDetector.pick(null, monoCap))
    }

    @Test
    fun curatedProfileWinsButNotForAMonoOrLogicalCamera() {
        assertEquals("2", MacroDetector.pick(verified("2"), specs))
        val mono4 = specs.map { if (it.id == "4") it.copy(monoColorFilter = true) else it }
        assertEquals("2", MacroDetector.pick(verified("4"), mono4))
        assertEquals("2", MacroDetector.pick(verified("5"), specs))
    }

    @Test
    fun closeFocusingSensorBeatsFixedFocus() {
        val close = CameraSpec("8", Facing.BACK, listOf(2.2f), 2.8f, 2.1f, 2048, 1536, 25f, caps)
        assertEquals("8", MacroDetector.pick(null, specs + close))
    }

    @Test
    fun noMacroOnPhonesWithoutOne() {
        val plain = specs.filter { it.id in setOf("0", "1", "3") }
        assertNull(MacroDetector.pick(null, plain))
        assertNull(MacroDetector.pick(null, emptyList()))
        val ordinaryAf = CameraSpec("9", Facing.BACK, listOf(2.2f), 2.8f, 2.1f, 1600, 1200, 10f, caps)
        assertNull(MacroDetector.pick(null, plain + ordinaryAf))
    }
}
