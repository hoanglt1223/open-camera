/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlRuntime
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Face detection (short-range detector, 128x128) followed by a 478-point face mesh on each
 * face crop. Coordinates are in source image pixels. Upright faces only: the crop is not
 * rotated, which is enough for masks (skin, eyes, lips) on photos taken with the phone level.
 */
class FaceMesh(private val runtime: MlRuntime) {
    class Face(val box: RectF, val score: Float, val landmarks: List<PointF>) {
        fun region(indices: IntArray) = indices.map { landmarks[it] }
    }

    val available get() = runtime.isAvailable(DETECTOR) && runtime.isAvailable(LANDMARKS)

    fun detectBoxes(image: Bitmap, maxFaces: Int = 4): List<Pair<RectF, Float>>? {
        if (!runtime.isAvailable(DETECTOR)) return null
        val small = ImageTensors.scaled(image, DET_SIZE, DET_SIZE)
        val t = ImageTensors.argbToNhwc(ImageTensors.pixels(small), DET_SIZE, DET_SIZE, mul = 2f, add = -1f)
        if (small !== image) small.recycle()
        val out = runtime.runOrNull(DETECTOR, listOf(t)) ?: return null
        val (reg, cls) = if (out[0].shape.last() == 16) out[0] to out[1] else out[1] to out[0]
        return decode(reg.data, cls.data, image.width, image.height, maxFaces)
    }

    /** Null when the models are unavailable; empty when no face is found. */
    fun detect(image: Bitmap, maxFaces: Int = 4): List<Face>? {
        val boxes = detectBoxes(image, maxFaces) ?: return null
        if (!runtime.isAvailable(LANDMARKS)) return null
        return boxes.mapNotNull { (box, score) -> landmarks(image, box, score) }
    }

    private fun landmarks(image: Bitmap, box: RectF, score: Float): Face? {
        val side = max(box.width(), box.height()) * CROP_SCALE
        val cx = box.centerX()
        val cy = box.centerY()
        val crop = Rect(
            (cx - side / 2).roundToInt(), (cy - side / 2).roundToInt(),
            (cx + side / 2).roundToInt(), (cy + side / 2).roundToInt(),
        )
        val clipped = Rect(crop).apply { if (!intersect(0, 0, image.width, image.height)) return null }
        // Paint the clipped part into a square canvas so out-of-image areas stay black.
        val square = Bitmap.createBitmap(LM_SIZE, LM_SIZE, Bitmap.Config.ARGB_8888)
        val k = LM_SIZE / side
        val dst = Rect(
            ((clipped.left - crop.left) * k).roundToInt(), ((clipped.top - crop.top) * k).roundToInt(),
            ((clipped.right - crop.left) * k).roundToInt(), ((clipped.bottom - crop.top) * k).roundToInt(),
        )
        android.graphics.Canvas(square).drawBitmap(image, clipped, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val t = ImageTensors.argbToNhwc(ImageTensors.pixels(square), LM_SIZE, LM_SIZE)
        square.recycle()
        val out = runtime.runOrNull(LANDMARKS, listOf(t)) ?: return null
        val pts = out.first { it.size == POINTS * 3 }.data
        val presence = out.firstOrNull { it.size == 1 }?.data?.get(0)?.let(::sigmoid) ?: 1f
        if (presence < MIN_PRESENCE) return null
        val lm = (0 until POINTS).map { i ->
            PointF(crop.left + pts[i * 3] / k, crop.top + pts[i * 3 + 1] / k)
        }
        return Face(box, score, lm)
    }

    companion object {
        const val DETECTOR = "face_detector"
        const val LANDMARKS = "face_landmarks"
        private const val DET_SIZE = 128
        private const val LM_SIZE = 256
        private const val POINTS = 478
        private const val CROP_SCALE = 1.5f
        private const val MIN_SCORE = 0.5f
        private const val MIN_PRESENCE = 0.5f
        private const val NMS_IOU = 0.3f

        // Landmark index sets of the public 478-point face mesh topology.
        val FACE_OVAL = intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
        val LEFT_EYE = intArrayOf(362, 398, 384, 385, 386, 387, 388, 466, 263, 249, 390, 373, 374, 380, 381, 382)
        val RIGHT_EYE = intArrayOf(33, 246, 161, 160, 159, 158, 157, 173, 133, 155, 154, 153, 145, 144, 163, 7)
        val LIPS = intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291, 375, 321, 405, 314, 17, 84, 181, 91, 146)
        val LEFT_BROW = intArrayOf(276, 283, 282, 295, 285, 300, 293, 334, 296, 336)
        val RIGHT_BROW = intArrayOf(46, 53, 52, 65, 55, 70, 63, 105, 66, 107)

        /** Anchor centres of the short-range detector: 16x16x2 then 8x8x6, unit size. */
        val ANCHORS: FloatArray by lazy {
            val a = ArrayList<Float>(896 * 2)
            for ((grid, perCell) in listOf(16 to 2, 8 to 6)) {
                for (y in 0 until grid) for (x in 0 until grid) repeat(perCell) {
                    a += (x + 0.5f) / grid
                    a += (y + 0.5f) / grid
                }
            }
            a.toFloatArray()
        }

        fun sigmoid(x: Float) = 1f / (1f + exp(-x.coerceIn(-100f, 100f)))

        /** Decodes boxes in image pixels, highest score first, after non-maximum suppression. */
        fun decode(reg: FloatArray, cls: FloatArray, width: Int, height: Int, maxFaces: Int): List<Pair<RectF, Float>> {
            val anchors = ANCHORS
            val n = anchors.size / 2
            val found = ArrayList<Pair<RectF, Float>>()
            for (i in 0 until n) {
                val s = sigmoid(cls[i])
                if (s < MIN_SCORE) continue
                val o = i * 16
                val cx = reg[o] / DET_SIZE + anchors[i * 2]
                val cy = reg[o + 1] / DET_SIZE + anchors[i * 2 + 1]
                val w = reg[o + 2] / DET_SIZE
                val h = reg[o + 3] / DET_SIZE
                found += RectF((cx - w / 2) * width, (cy - h / 2) * height, (cx + w / 2) * width, (cy + h / 2) * height) to s
            }
            found.sortByDescending { it.second }
            val kept = ArrayList<Pair<RectF, Float>>()
            for (f in found) {
                if (kept.size >= maxFaces) break
                if (kept.none { iou(it.first, f.first) > NMS_IOU }) kept += f
            }
            return kept
        }

        fun iou(a: RectF, b: RectF): Float {
            val iw = min(a.right, b.right) - max(a.left, b.left)
            val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (iw <= 0 || ih <= 0) return 0f
            val inter = iw * ih
            return inter / (a.width() * a.height() + b.width() * b.height() - inter)
        }
    }
}
