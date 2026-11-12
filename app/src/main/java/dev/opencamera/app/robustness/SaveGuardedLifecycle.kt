/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.robustness

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides the state of the lifecycle the camera is bound to. Unbinding ImageCapture (which
 * CameraX does when its lifecycle drops below STARTED) aborts shots that are still exposing or
 * saving, so the drop waits for them, but never longer than the caller's bound.
 */
object CameraStopGate {
    fun target(source: Lifecycle.State, pendingSaves: Int, waitExpired: Boolean): Lifecycle.State =
        if (source.isAtLeast(Lifecycle.State.STARTED) || pendingSaves == 0 || waitExpired) {
            source
        } else {
            Lifecycle.State.STARTED
        }
}

/**
 * Follows [source] but holds STARTED while [pendingSaves] > 0 after the source stopped, for at
 * most [maxWaitMs]. Bind the camera controller to this instead of the activity.
 */
class SaveGuardedLifecycle(
    private val source: Lifecycle,
    private val pendingSaves: StateFlow<Int>,
    private val maxWaitMs: Long,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wait: Job? = null

    override val lifecycle: Lifecycle get() = registry

    init {
        source.addObserver(LifecycleEventObserver { _, _ -> update() })
    }

    private fun update() {
        val state = source.currentState
        val target = CameraStopGate.target(state, pendingSaves.value, waitExpired = false)
        apply(target)
        if (target == state) {
            wait?.cancel()
            wait = null
            return
        }
        if (wait?.isActive == true) return
        Log.i(LOG_TAG, "Camera kept bound in $state: ${pendingSaves.value} photos still saving")
        wait = scope.launch {
            val drained = withTimeoutOrNull(maxWaitMs) { pendingSaves.first { it == 0 } } != null
            if (!drained) Log.w(LOG_TAG, "Saves still pending after $maxWaitMs ms, releasing the camera")
            wait = null
            apply(CameraStopGate.target(source.currentState, pendingSaves.value, waitExpired = !drained))
        }
    }

    private fun apply(target: Lifecycle.State) {
        if (registry.currentState == target) return
        // LifecycleRegistry forbids leaving DESTROYED and moving out of INITIALIZED to DESTROYED.
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (target == Lifecycle.State.DESTROYED && registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.currentState = target
        if (target == Lifecycle.State.DESTROYED) scope.cancel()
    }

    private companion object {
        const val LOG_TAG = "SaveGuardedLifecycle"
    }
}
