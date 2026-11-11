/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Unblurred originals of recent portrait shots, kept privately so the editor can change the
 * aperture or focus point after capture. Bounded to [MAX_KEPT] files; the oldest go first.
 */
object PortraitOriginals {
    private const val TAG = "PortraitOriginals"
    private const val MAX_KEPT = 30

    private fun dir(context: Context) = File(context.filesDir, "portrait-originals").apply { mkdirs() }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun keep(context: Context, source: Uri, displayName: String) {
        try {
            val target = File(dir(context), "${safe(displayName)}.jpg")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_KEPT)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot keep original of $displayName", e)
        }
    }

    /** Original for a gallery display name (with or without extension), if still kept. */
    fun find(context: Context, displayName: String): File? =
        File(dir(context), "${safe(displayName.substringBeforeLast('.'))}.jpg").takeIf { it.isFile }
}
