/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.export

import android.graphics.Bitmap
import androidx.heifwriter.HeifWriter
import java.io.File

/** HEIF still through the device's HEVC encoder (androidx HeifWriter). */
object HeifEncoder {
    fun write(bitmap: Bitmap, quality: Int, file: File) {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        HeifWriter.Builder(file.absolutePath, src.width, src.height, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(quality.coerceIn(0, 100))
            .setMaxImages(1)
            .build()
            .use { w ->
                w.start()
                w.addBitmap(src)
                w.stop(TIMEOUT_MS)
            }
        if (src !== bitmap) src.recycle()
    }

    private const val TIMEOUT_MS = 15_000L
}
