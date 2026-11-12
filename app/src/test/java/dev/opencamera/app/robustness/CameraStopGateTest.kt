/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.robustness

import androidx.lifecycle.Lifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStopGateTest {
    @Test
    fun followsTheSourceWhileStartedOrResumed() {
        for (state in listOf(State.STARTED, State.RESUMED)) {
            assertEquals(state, CameraStopGate.target(state, pendingSaves = 2, waitExpired = false))
        }
    }

    @Test
    fun stopsAtOnceWhenNothingIsSaving() {
        assertEquals(State.CREATED, CameraStopGate.target(State.CREATED, pendingSaves = 0, waitExpired = false))
        assertEquals(State.DESTROYED, CameraStopGate.target(State.DESTROYED, pendingSaves = 0, waitExpired = false))
    }

    @Test
    fun holdsStartedWhileAPhotoIsSaving() {
        assertEquals(State.STARTED, CameraStopGate.target(State.CREATED, pendingSaves = 1, waitExpired = false))
        assertEquals(State.STARTED, CameraStopGate.target(State.DESTROYED, pendingSaves = 1, waitExpired = false))
    }

    @Test
    fun releasesTheCameraOnceTheWaitExpires() {
        assertEquals(State.CREATED, CameraStopGate.target(State.CREATED, pendingSaves = 1, waitExpired = true))
    }
}
