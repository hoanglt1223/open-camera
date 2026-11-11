/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import dev.opencamera.ml.tasks.Mask

/** Orientation helpers: models see upright images, results map back to sensor orientation. */
object Masks {
    fun rotated(src: Bitmap, degrees: Int): Bitmap {
        val d = norm(degrees)
        if (d == 0) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(d.toFloat()) }, true)
    }

    /**
     * Maps a mask computed on the image rotated clockwise by [degrees] back to the unrotated
     * [w] x [h] image, resampling bilinearly.
     */
    fun unrotate(upright: Mask, degrees: Int, w: Int, h: Int): Mask {
        val out = Mask(w, h)
        val d = norm(degrees)
        for (y in 0 until h) for (x in 0 until w) {
            val u = (x + 0.5f) / w
            val v = (y + 0.5f) / h
            out.data[y * w + x] = when (d) {
                90 -> upright.sample(1f - v, u)
                180 -> upright.sample(1f - u, 1f - v)
                270 -> upright.sample(v, 1f - u)
                else -> upright.sample(u, v)
            }
        }
        return out
    }

    /** Normalized point on the upright image to the unrotated image. */
    fun unrotatePoint(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (norm(degrees)) {
        90 -> y to 1f - x
        180 -> 1f - x to 1f - y
        270 -> 1f - y to x
        else -> x to y
    }

    private fun norm(d: Int) = ((d % 360) + 360) % 360
}
