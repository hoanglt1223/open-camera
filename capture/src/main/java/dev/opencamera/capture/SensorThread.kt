/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import android.os.Handler
import android.os.HandlerThread

/** One background looper for high-rate sensor callbacks, so they never queue on the main thread. */
object SensorThread {
    val handler: Handler by lazy { Handler(HandlerThread("sensors").apply { start() }.looper) }
}