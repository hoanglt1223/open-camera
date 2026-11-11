/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.assist

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager

/**
 * Feeds [AssistOverlayView]: the level from the gravity sensor (accelerometer when there is no
 * gravity sensor) while the activity is resumed, and composition hints pushed by the analysis.
 * Without either sensor the level stays hidden; nothing here can stop the camera.
 */
class AssistController(
    context: Context,
    private val view: AssistOverlayView,
    private val isPhotoMode: () -> Boolean,
) : DefaultLifecycleObserver, SensorEventListener {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val haptic = LevelMath.Haptic()
    private val g = FloatArray(3)
    private var primed = false
    private var lastDraw = 0L
    private var hintsDismissed = false

    val showHints get() = prefs.getBoolean(KEY_HINTS, false)

    /** Level on/off (the "leveler" setting); the sensor only runs while on and resumed. */
    var levelEnabled = false
        set(value) {
            field = value
            updateSensor()
        }
    private var resumed = false
    private var registered = false

    /** Last horizon error, for the straighten hint. */
    @Volatile
    var rollDeg: Float? = null
        private set

    init {
        // Tapping a hint hides hints for the rest of the session.
        view.setOnClickListener {
            hintsDismissed = true
            view.hints = emptyList()
            view.isClickable = false
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        resumed = true
        updateSensor()
    }

    override fun onPause(owner: LifecycleOwner) {
        resumed = false
        updateSensor()
    }

    private fun updateSensor() {
        val want = resumed && levelEnabled && sensor != null
        if (want == registered) return
        if (want) {
            primed = false
            registered = try {
                sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Level sensor unavailable", e)
                false
            }
        } else {
            runCatching { sensors?.unregisterListener(this) }
            registered = false
            view.level = null
            rollDeg = null
        }
    }
    /** From the composition analysis (main thread). Null fields mean no subject. */
    fun onComposition(subjectX: Float?, subjectY: Float?, targetX: Float?, targetY: Float?, subjectTop: Float?) {
        if (!showHints || hintsDismissed || !isPhotoMode()) {
            view.hints = emptyList()
            view.isClickable = false
            return
        }
        val hints = CompositionHints.compute(subjectX, subjectY, targetX, targetY, subjectTop, rollDeg)
        view.hints = hints
        view.isClickable = hints.isNotEmpty()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (!primed) {
            v.copyInto(g, 0, 0, 3)
            primed = true
        } else {
            for (i in 0..2) g[i] += SMOOTHING * (v[i] - g[i])
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastDraw < DRAW_INTERVAL_MS) return
        lastDraw = now
        val reading = LevelMath.read(g[0], g[1], g[2])
        rollDeg = (reading as? LevelMath.Reading.Horizon)?.rollDeg
        view.level = reading
        if (reading != null && haptic.shouldTick(reading)) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "AssistController"
        const val KEY_HINTS = "assist_composition_hints"
        private const val SMOOTHING = 0.25f
        private const val DRAW_INTERVAL_MS = 50L
    }
}
