/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.ml.ML_RUNTIME_INCLUDED
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.FaceMesh
import kotlin.math.max

/**
 * Reads a saved photo back at [ShotCheck.ANALYSIS_SIDE] px and judges it with [ShotCheck].
 * Blocking; run off the main thread. Any failure means "no warning".
 */
class ShotChecker(context: Context) {
    private val app = context.applicationContext
    private val faces by lazy {
        if (!ML_RUNTIME_INCLUDED) null else runCatching { FaceMesh(MlRuntime.get(app)).takeIf { it.available } }.getOrNull()
    }

    fun check(uri: Uri): ShotCheck.Verdict = runCatching {
        val t0 = SystemClock.elapsedRealtime()
        // Some save paths finish writing (gain map, motion photo) just after reporting the uri.
        var bitmap: Bitmap? = null
        for (attempt in 0 until DECODE_ATTEMPTS) {
            bitmap = runCatching { decode(uri) }.getOrNull()
            if (bitmap != null) break
            Thread.sleep(DECODE_RETRY_MS)
        }
        bitmap ?: return ShotCheck.Verdict.OK.also { Log.i(TAG, "$uri: not decodable, skipped") }
        try {
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val y = ByteArray(w * h) { i ->
                val c = px[i]
                ((77 * (c shr 16 and 0xff) + 150 * (c shr 8 and 0xff) + 29 * (c and 0xff) + 128) shr 8).toByte()
            }
            val sharpness = ShotCheck.tileSharpness(y, w, h)
            val contrast = ShotCheck.contrast(y, w, h)
            val found = faces?.detect(bitmap, MAX_FACES)?.mapNotNull { f ->
                SmartShutter.faceState(f.landmarks)?.let { ShotCheck.Face(it.eyes, f.box.width() / w) }
            }
            ShotCheck.verdict(sharpness, contrast, found).also {
                Log.i(TAG, "$uri: $it sharpness=${"%.1f".format(sharpness)} contrast=${"%.0f".format(contrast)} " +
                    "faces=${found?.joinToString { f -> "%.2f".format(f.eyes) }} ${SystemClock.elapsedRealtime() - t0} ms")
            }
        } finally {
            bitmap.recycle()
        }
    }.onFailure { Log.w(TAG, "Shot check failed for $uri", it) }.getOrDefault(ShotCheck.Verdict.OK)

    /** The photo upright, long side at most [ShotCheck.ANALYSIS_SIDE]. */
    private fun decode(uri: Uri): Bitmap? {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decoding always returns null; the size lands in [bounds].
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = max(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= ShotCheck.ANALYSIS_SIDE) sample *= 2
        val raw = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val s = ShotCheck.ANALYSIS_SIDE.toFloat() / max(raw.width, raw.height)
        val m = Matrix().apply {
            if (s < 1f) postScale(s, s)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        if (m.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    private companion object {
        const val TAG = "ShotCheck"
        const val MAX_FACES = 6
        const val DECODE_ATTEMPTS = 4
        const val DECODE_RETRY_MS = 750L
    }
}
