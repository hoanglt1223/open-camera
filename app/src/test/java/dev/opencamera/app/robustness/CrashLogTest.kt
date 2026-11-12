/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.robustness

import dev.opencamera.app.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashLogTest {
    @Test
    fun keepsNewestFiveAndSharesLatest() {
        val dir = Files.createTempDirectory("crash").toFile()
        try {
            assertNull(CrashLog.latest(dir))
            for (t in 1L..7L) CrashLog.write(dir, t, CrashLog.format("hdr", "main", IllegalStateException("boom $t")))
            assertEquals(5, CrashLog.reports(dir).size)
            val text = CrashLog.latest(dir)!!
            assertTrue(text.contains("boom 7"))
            assertTrue(text.contains("Thread: main"))
            assertTrue(text.contains("IllegalStateException"))
            CrashLog.clear(dir)
            assertNull(CrashLog.latest(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
