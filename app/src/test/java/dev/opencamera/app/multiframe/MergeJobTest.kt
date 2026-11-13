/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import dev.opencamera.capture.BurstKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeJobTest {
    @Test
    fun ultraHdrChoiceSurvivesArchive() {
        val job = MergeJob(BurstKind.HDR, 1f, null, 90, 1L, null, ultraHdr = true)
        assertTrue(MergeJob.fromMap(job.toMap())!!.ultraHdr)
        assertEquals(job.copy(exposure = ExposureExif(null, null, null, null)), MergeJob.fromMap(job.toMap()))
    }

    @Test
    fun archivesWithoutTheKeyStayPlainJpeg() {
        val legacy = MergeJob(BurstKind.NIGHT, 2f, null, 0, 1L, null).toMap() - "uhdr"
        assertFalse(MergeJob.fromMap(legacy)!!.ultraHdr)
    }
}
