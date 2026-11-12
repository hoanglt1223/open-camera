/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GainmapCarryTest {
    @Test
    fun gainmapOnlyCarriedToSameGeometry() {
        assertTrue(StillProcessor.keepsGainmap(4000, 3000, 4000, 3000, outHasGainmap = false))
        // Frame border or upscale: the output falls back to plain JPEG.
        assertFalse(StillProcessor.keepsGainmap(4000, 3000, 4200, 3400, outHasGainmap = false))
        // Output already carries its own gain map.
        assertFalse(StillProcessor.keepsGainmap(4000, 3000, 4000, 3000, outHasGainmap = true))
    }
}
