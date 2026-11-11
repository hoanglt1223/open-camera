/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app

import android.content.pm.ApplicationInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log

/**
 * Debuggable builds only: StrictMode logs disk/network/slow calls and leaks, and a watchdog
 * logs the main thread's stack whenever it has been blocked for more than [BLOCK_MS].
 * Both only log; nothing is killed.
 */
object DebugDiagnostics {
    private const val TAG = "MainWatchdog"
    private const val BLOCK_MS = 500L
    private const val TICK_MS = 250L

    fun install(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        startWatchdog()
        dev.opencamera.app.ui.overlay.RecomposeCounts.enabled = true
    }

    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val beat = java.util.concurrent.atomic.AtomicLong(SystemClock.uptimeMillis())
            var reported = 0L
            while (true) {
                main.post { beat.set(SystemClock.uptimeMillis()) }
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val lastBeat = beat.get()
                val blocked = SystemClock.uptimeMillis() - lastBeat - TICK_MS
                if (blocked > BLOCK_MS && lastBeat != reported) {
                    reported = lastBeat
                    val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n  at ")
                    Log.w(TAG, "Main thread blocked for $blocked ms:\n  at $stack")
                }
            }
        }, "main-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}