/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlRuntime
import kotlin.math.max

/**
 * Open-palm detection with the MediaPipe palm detector (192x192, inputs 0..1). The frame is
 * letterboxed into a square first, as the detector was trained on. Only the best score and its
 * box are reported: enough to trigger the gesture timer.
 */
class PalmDetector(private val runtime: MlRuntime) {
    /** Box centre and width in the source frame, normalized 0..1. */
    data class Palm(val score: Float, val cx: Float, val cy: Float, val width: Float)

    val available get() = runtime.isAvailable(MODEL)

    /** Null when the model is unavailable or inference failed. */
    fun detect(image: Bitmap): Palm? {
        if (!available) return null
        val square = letterbox(image)
        val t = ImageTensors.argbToNhwc(ImageTensors.pixels(square), SIZE, SIZE)
        square.recycle()
        val out = runtime.runOrNull(MODEL, listOf(t)) ?: return null
        val (reg, cls) = if (out[0].shape.last() == 18) out[0] to out[1] else out[1] to out[0]
        return decodeBest(reg.data, cls.data, image.width, image.height)
    }

    private fun letterbox(source: Bitmap): Bitmap {
        // Halve first: one bilinear step from a large frame aliases badly and lowers scores.
        var image = source
        while (max(image.width, image.height) > SIZE * 2) {
            val half = Bitmap.createScaledBitmap(image, max(1, image.width / 2), max(1, image.height / 2), true)
            if (image !== source) image.recycle()
            image = half
        }
        val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val s = SIZE.toFloat() / max(image.width, image.height)
        val w = image.width * s
        val h = image.height * s
        val dst = android.graphics.RectF((SIZE - w) / 2, (SIZE - h) / 2, (SIZE + w) / 2, (SIZE + h) / 2)
        Canvas(square).drawBitmap(image, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        if (image !== source) image.recycle()
        return square
    }

    companion object {
        const val MODEL = "palm_detector"
        private const val SIZE = 192

        /** Anchor centres: 24x24 cells with 2 anchors, then 12x12 with 6 (2016 in all). */
        val ANCHORS: FloatArray by lazy {
            val a = ArrayList<Float>(2016 * 2)
            for ((grid, perCell) in listOf(24 to 2, 12 to 6)) {
                for (y in 0 until grid) for (x in 0 until grid) repeat(perCell) {
                    a += (x + 0.5f) / grid
                    a += (y + 0.5f) / grid
                }
            }
            a.toFloatArray()
        }

        /**
         * Best palm in a [width] x [height] frame from raw outputs ([reg] 18 values per anchor,
         * [cls] logits), with coordinates mapped back out of the letterbox. Null on bad sizes.
         */
        fun decodeBest(reg: FloatArray, cls: FloatArray, width: Int, height: Int): Palm? {
            val anchors = ANCHORS
            val n = anchors.size / 2
            if (cls.size < n || reg.size < n * 18 || width <= 0 || height <= 0) return null
            var best = -1
            for (i in 0 until n) if (best < 0 || cls[i] > cls[best]) best = i
            val score = FaceMesh.sigmoid(cls[best])
            val o = best * 18
            val cx = reg[o] / SIZE + anchors[best * 2]
            val cy = reg[o + 1] / SIZE + anchors[best * 2 + 1]
            val w = reg[o + 2] / SIZE
            // Undo the letterbox: the frame occupies the centred fraction of the square.
            val long = max(width, height).toFloat()
            val fx = width / long
            val fy = height / long
            return Palm(
                score,
                ((cx - (1 - fx) / 2) / fx).coerceIn(0f, 1f),
                ((cy - (1 - fy) / 2) / fy).coerceIn(0f, 1f),
                (w / fx).coerceIn(0f, 1f),
            )
        }
    }
}
