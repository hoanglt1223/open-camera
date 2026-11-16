/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

import dev.opencamera.app.modes.MacroSuggester.Companion.AF_PASSIVE_FOCUSED
import dev.opencamera.app.modes.MacroSuggester.Companion.AF_PASSIVE_SCAN
import dev.opencamera.app.modes.MacroSuggester.Companion.AF_PASSIVE_UNFOCUSED
import dev.opencamera.app.modes.MacroSuggester.Companion.HIDE_FRAMES
import dev.opencamera.app.modes.MacroSuggester.Companion.SHOW_FRAMES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroSuggesterTest {
    private fun MacroSuggester.feed(n: Int, af: Int?, dist: Float?) = (1..n).map { onFrame(af, dist) }.last()

    @Test
    fun suggestsOnlyAfterTheLensStaysAtClosestFocusUnfocused() {
        val s = MacroSuggester(10f)
        assertFalse(s.feed(SHOW_FRAMES - 1, AF_PASSIVE_UNFOCUSED, 10f))
        assertTrue(s.onFrame(AF_PASSIVE_SCAN, 9.5f))
    }

    @Test
    fun focusedOrFarSubjectsNeverSuggest() {
        val s = MacroSuggester(10f)
        assertFalse(s.feed(100, AF_PASSIVE_FOCUSED, 10f))
        assertFalse(s.feed(100, AF_PASSIVE_UNFOCUSED, 2f))
    }

    @Test
    fun hidesWithHysteresis() {
        val s = MacroSuggester(10f)
        s.feed(SHOW_FRAMES, AF_PASSIVE_UNFOCUSED, 10f)
        assertTrue(s.feed(HIDE_FRAMES - 1, AF_PASSIVE_FOCUSED, 3f))
        assertFalse(s.onFrame(AF_PASSIVE_FOCUSED, 3f))
    }

    @Test
    fun repeatedFocusFailuresSuggestWithinTheWindow() {
        val s = MacroSuggester(10f)
        assertFalse(s.onFocusFailed(0))
        assertFalse(s.onFocusFailed(9_000))
        assertFalse(s.onFocusFailed(10_000))
        assertTrue(s.onFocusFailed(11_000))
    }

    @Test
    fun fixedFocusCameraOrMissingMetadataIsIgnored() {
        assertFalse(MacroSuggester(0f).feed(100, AF_PASSIVE_UNFOCUSED, 0f))
        val s = MacroSuggester(10f)
        assertFalse(s.feed(100, null, 10f))
        assertFalse(s.feed(100, AF_PASSIVE_UNFOCUSED, null))
        s.feed(SHOW_FRAMES, AF_PASSIVE_UNFOCUSED, 10f)
        s.reset()
        assertFalse(s.suggested)
    }
}
