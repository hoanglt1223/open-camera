/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.robustness

import dev.opencamera.app.viewmodels.CameraViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZslPolicyTest {
    @Test
    fun zslOnlyWhenSupportedAndNotFailed() {
        assertTrue(CameraViewModel.useZsl(enabled = true, supported = true, failed = false, extension = false))
        assertFalse(CameraViewModel.useZsl(enabled = true, supported = false, failed = false, extension = false))
        // A ZSL bind failure falls back to the regular capture mode for the rest of the process.
        assertFalse(CameraViewModel.useZsl(enabled = true, supported = true, failed = true, extension = false))
        // The phone's extension processing wins over ZSL.
        assertFalse(CameraViewModel.useZsl(enabled = true, supported = true, failed = false, extension = true))
        assertFalse(CameraViewModel.useZsl(enabled = false, supported = true, failed = false, extension = false))
    }
}
