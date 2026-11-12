/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Opt-in local crash reports. Off by default; when the user enables it an uncaught exception is
 * written to app storage before the default handler runs. Nothing is sent anywhere: the user
 * shares a report themselves from Settings.
 */
object CrashLog {
    const val PREF_KEY = "crash_reports"
    private const val DIR = "crash"
    private const val KEEP = 5
    private const val MAX_SHARE_CHARS = 100_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (PreferenceManager.getDefaultSharedPreferences(app).getBoolean(PREF_KEY, false)) {
                    write(dir(app), System.currentTimeMillis(), format(header(app), thread.name, error))
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun dir(context: Context) = File(context.filesDir, DIR)

    /** Newest report text, trimmed for sharing, or null when there is none. */
    fun latest(dir: File): String? = reports(dir).firstOrNull()?.let { f ->
        runCatching { f.readText().take(MAX_SHARE_CHARS) }.getOrNull()
    }

    fun clear(dir: File) {
        reports(dir).forEach { it.delete() }
    }

    internal fun reports(dir: File): List<File> =
        (dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }

    internal fun write(dir: File, timeMs: Long, text: String) {
        dir.mkdirs()
        File(dir, "crash-%013d.txt".format(timeMs)).writeText(text)
        reports(dir).drop(KEEP).forEach { it.delete() }
    }

    internal fun format(header: String, threadName: String, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        return "$header\nThread: $threadName\n\n$trace"
    }

    private fun header(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        return "Open Camera ${version ?: "?"} (${context.packageName})\n" +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
