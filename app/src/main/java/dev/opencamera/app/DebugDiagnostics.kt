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
 *
 * Main-thread violations whose stack passes through our own code are also logged under
 * [STRICT_TAG] as `VIOLATION …`; the macrobenchmark StrictModeCheck fails on any such line. The
 * profileable benchmark build turns the same recorder on when launched with [EXTRA_STRICT_CHECK].
 */
object DebugDiagnostics {
    private const val TAG = "MainWatchdog"
    const val STRICT_TAG = "OcStrictMode"
    const val EXTRA_STRICT_CHECK = "dev.opencamera.extra.STRICT_CHECK"
    private const val BLOCK_MS = 500L
    private const val TICK_MS = 250L
    private const val OWN_PACKAGE = "dev.opencamera."

    @Volatile
    private var recording = false

    fun install(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(threadPolicy())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        recording = true
        startWatchdog()
        dev.opencamera.app.ui.overlay.RecomposeCounts.enabled = true
    }

    /** Benchmark builds (profileable, not debuggable): records main-thread violations on request. */
    fun enableStrictCheck(context: Context, intent: android.content.Intent?) {
        if (recording || intent?.getBooleanExtra(EXTRA_STRICT_CHECK, false) != true) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        if (!context.applicationInfo.isProfileableByShell) return
        recording = true
        StrictMode.setThreadPolicy(threadPolicy())
    }

    private fun threadPolicy(): StrictMode.ThreadPolicy {
        val b = StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            b.penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { v -> record(v) }
        }
        return b.build()
    }

    private fun record(v: Throwable) {
        // Framework and vendor code (CameraX, MIUI hooks) reads on main too; only our own frames count.
        val frames = v.stackTrace
        val own = frames.firstOrNull { it.className.startsWith(OWN_PACKAGE) } ?: return
        Log.e(STRICT_TAG, "VIOLATION ${v.javaClass.simpleName} at $own\n  at " + frames.take(40).joinToString("\n  at "))
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