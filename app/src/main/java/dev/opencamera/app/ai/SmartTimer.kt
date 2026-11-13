/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import dev.opencamera.ml.ML_RUNTIME_INCLUDED
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.ml.tasks.PalmDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Smart self-timer: watches downscaled viewfinder frames for a raised open palm or for everyone
 * smiling with open eyes ([SmartShutter]). Frames come from [frame] (main thread, e.g. the
 * preview view's bitmap), so the camera session is never rebound for it.
 */
class SmartTimer(
    context: Context,
    private val scope: CoroutineScope,
    private val frame: () -> Bitmap?,
) {
    private val app = context.applicationContext
    private val runtime by lazy { MlRuntime.get(app) }
    private val palms by lazy { runCatching { PalmDetector(runtime) }.getOrNull() }
    private val faces by lazy { runCatching { FaceMesh(runtime) }.getOrNull() }
    private var job: Job? = null

    val active get() = job?.isActive == true

    /** Blocking (model registry); true when at least one of the two signals can be read. */
    fun available(): Boolean = ML_RUNTIME_INCLUDED && runCatching {
        palms?.available == true || faces?.available == true
    }.getOrDefault(false)

    /**
     * Starts watching; [onTrigger] runs once on the main thread. After [TIMEOUT_MS] without a
     * signal [onTimeout] runs instead.
     */
    fun start(onTrigger: (SmartShutter.Trigger) -> Unit, onTimeout: () -> Unit) {
        cancel()
        val decider = SmartShutter()
        job = scope.launch {
            val started = SystemClock.elapsedRealtime()
            while (isActive) {
                if (SystemClock.elapsedRealtime() - started > TIMEOUT_MS) {
                    onTimeout()
                    return@launch
                }
                delay(INTERVAL_MS)
                val full = runCatching { frame() }.getOrNull() ?: continue
                val trigger = withContext(Dispatchers.Default) {
                    runCatching { analyse(full, decider) }
                        .onFailure { Log.w(TAG, "Smart timer frame failed", it) }
                        .getOrDefault(SmartShutter.Trigger.NONE)
                }
                if (trigger != SmartShutter.Trigger.NONE && isActive) {
                    Log.i(TAG, "Smart timer fired: $trigger")
                    onTrigger(trigger)
                    return@launch
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private fun analyse(full: Bitmap, decider: SmartShutter): SmartShutter.Trigger {
        val s = ANALYSIS_SIDE.toFloat() / max(full.width, full.height)
        val small = if (s < 1f) {
            Bitmap.createScaledBitmap(full, (full.width * s).roundToInt(), (full.height * s).roundToInt(), true)
        } else {
            full
        }
        try {
            val palm = palms?.takeIf { it.available }?.detect(small)
            val found = faces?.takeIf { it.available }?.detect(small, MAX_FACES)
                ?.mapNotNull { SmartShutter.faceState(it.landmarks) }
            Log.d(TAG, "palm=${palm?.score} faces=${found?.joinToString { "${it.eyes}/${it.smile}" }}")
            return decider.onFrame(palm?.score, found)
        } finally {
            if (small !== full) small.recycle()
            full.recycle()
        }
    }

    companion object {
        private const val TAG = "SmartTimer"
        private const val INTERVAL_MS = 250L
        const val TIMEOUT_MS = 60_000L
        private const val ANALYSIS_SIDE = 640
        private const val MAX_FACES = 6
    }
}
