/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dev.opencamera.capture.SensorThread

/** Gyroscope at ~200 Hz; [listener] gets (timestampNs, wx, wy, wz) on [SensorThread], not the main thread. */
class GyroSensor(context: Context, private val listener: (Long, Float, Float, Float) -> Unit) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var running = false

    private val callback = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) =
            listener(event.timestamp, event.values[0], event.values[1], event.values[2])

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    val available get() = sensor != null

    fun start(): Boolean {
        if (running) return true
        val s = sensor ?: return false
        running = try {
            manager!!.registerListener(callback, s, 5_000, SensorThread.handler)
        } catch (e: Exception) {
            Log.w("GyroSensor", "register failed", e)
            false
        }
        return running
    }

    fun stop() {
        if (!running) return
        runCatching { manager?.unregisterListener(callback) }
        running = false
    }
}
