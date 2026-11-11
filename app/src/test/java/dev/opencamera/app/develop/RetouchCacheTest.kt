/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import dev.opencamera.app.develop.io.RetouchCache
import dev.opencamera.app.develop.model.ToolStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetouchCacheTest {
    private val steps = listOf(ToolStep("remove", """{"method":"migan"}"""), ToolStep("spot", "{}"))

    @Test
    fun keyIsStableAndCoversSourceSizeAndSteps() {
        val k = RetouchCache.key("abc", 1836, 2560, steps)
        assertEquals(k, RetouchCache.key("abc", 1836, 2560, steps.toList()))
        assertNotEquals(k, RetouchCache.key("abd", 1836, 2560, steps))
        assertNotEquals(k, RetouchCache.key("abc", 1835, 2560, steps))
        assertNotEquals(k, RetouchCache.key("abc", 1836, 2560, steps.take(1)))
        assertNotEquals(k, RetouchCache.key("abc", 1836, 2560, listOf(steps[0], steps[1].copy(enabled = false))))
        assertNotEquals(k, RetouchCache.key("abc", 1836, 2560, steps.reversed()))
    }

    @Test
    fun truncatedOrForeignEntriesAreMisses() {
        val magic = 0x52435431
        assertTrue(RetouchCache.headerMatches(magic, 4, 2, 4, 2, 12L + 32))
        assertFalse(RetouchCache.headerMatches(magic, 4, 2, 4, 2, 12L + 31))
        assertFalse(RetouchCache.headerMatches(magic, 4, 2, 2, 4, 12L + 32))
        assertFalse(RetouchCache.headerMatches(0, 4, 2, 4, 2, 12L + 32))
    }

    @Test
    fun evictsLeastRecentlyUsedUntilUnderBudget() {
        val e = listOf(
            RetouchCache.Entry("new", 60, 3),
            RetouchCache.Entry("old", 60, 1),
            RetouchCache.Entry("mid", 60, 2),
        )
        assertEquals(listOf("old", "mid"), RetouchCache.evictions(e, 100))
        assertEquals(emptyList<String>(), RetouchCache.evictions(e, 180))
    }
}
