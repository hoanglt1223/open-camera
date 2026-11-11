/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Feeds the gyroscope into a [TripodDetector] while started, on [SensorThread]; [state] may be read from any thread. */
class MotionMeter(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val detector = TripodDetector()
    private var started = false

    val available: Boolean
        get() = gyroscope != null

    /** Without a gyroscope, report moderate handheld motion and never a tripod. */
    fun state(): MotionState =
        if (gyroscope == null) MotionState(0.05f, false) else synchronized(detector) { detector.state() }

    fun start() {
        if (started || gyroscope == null) return
        synchronized(detector) { detector.reset() }
        started = sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, SensorThread.handler)
    }

    fun stop() {
        if (!started) return
        sensorManager?.unregisterListener(this)
        started = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(detector) { detector.add(event.timestamp, event.values[0], event.values[1], event.values[2]) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
