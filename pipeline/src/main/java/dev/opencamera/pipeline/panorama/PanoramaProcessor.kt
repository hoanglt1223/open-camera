/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.panorama

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** Pure geometry of the cylindrical sweep, kept apart from OpenCV for unit tests. */
object PanoramaMath {
    /** Expected horizontal offset in cylinder pixels between frames turned by [deltaYawDeg]. */
    fun expectedShift(deltaYawDeg: Float, focalPx: Float) =
        abs(Math.toRadians(deltaYawDeg.toDouble()) * focalPx).toFloat()

    /** Rows lost at the top and bottom of a cylinder-warped frame (the curved corners). */
    fun cylinderCropRows(width: Int, height: Int, focalPx: Float): Int {
        val thetaMax = (width / 2f) / focalPx
        return (height / 2f * (1f - cos(thetaMax))).roundToInt()
    }

    /** True when a sweep that increases yaw moves right to left and must be reversed. */
    fun isReversed(yawDegs: List<Float>) = yawDegs.size > 1 && yawDegs.last() > yawDegs.first()

    /** Accepts a phase-correlation refinement only when it is small next to the gyro estimate. */
    fun refine(expected: Float, shiftX: Double, response: Double, width: Int): Float {
        val refined = expected - shiftX.toFloat()
        return if (response > 0.05 && abs(shiftX) < width * 0.2 && refined > 0) refined else expected
    }
}

/**
 * Panorama from gyro-spaced frames: cylindrical warp, gyro offsets refined by phase correlation,
 * feathered seams. OpenCV's C++ Stitcher has no Java binding, and calling it through JNI would
 * need OpenCV's shared libc++; the sweep gives good rotation priors, so this simpler stitcher is
 * enough for a single-row panorama.
 */
object PanoramaProcessor {
    class Frame(val bitmap: Bitmap, val yawDeg: Float)

    private const val MAX_HEIGHT = 1280

    /** Returns the stitched panorama, or null when there are too few usable frames. */
    fun stitch(input: List<Frame>, focalPxFullRes: Float): Bitmap? {
        if (input.size < 2) return null
        val frames = if (PanoramaMath.isReversed(input.map { it.yawDeg })) input.reversed() else input
        val src0 = frames[0].bitmap
        val scale = min(1f, MAX_HEIGHT.toFloat() / src0.height)
        val w = (src0.width * scale).roundToInt()
        val h = (src0.height * scale).roundToInt()
        val f = focalPxFullRes * scale

        val (mapX, mapY) = cylinderMaps(w, h, f)
        val warped = frames.map { frame ->
            val rgba = Mat()
            val scaled = Bitmap.createScaledBitmap(frame.bitmap, w, h, true)
            Utils.bitmapToMat(scaled, rgba)
            if (scaled !== frame.bitmap) scaled.recycle()
            val out = Mat()
            Imgproc.remap(rgba, out, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0))
            rgba.release()
            out
        }
        mapX.release(); mapY.release()

        val xs = IntArray(frames.size)
        val ys = IntArray(frames.size)
        for (i in 1 until frames.size) {
            val expected = PanoramaMath.expectedShift(frames[i].yawDeg - frames[i - 1].yawDeg, f)
            val (dx, dy) = offset(warped[i - 1], warped[i], expected, w)
            xs[i] = xs[i - 1] + dx
            ys[i] = ys[i - 1] + dy
        }

        val minY = ys.min()
        val maxY = ys.max()
        val canvasW = xs.last() + w
        val canvasH = h + (maxY - minY)
        val canvas = IntArray(canvasW * canvasH)
        val row = IntArray(w)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        warped.forEachIndexed { i, mat ->
            Utils.matToBitmap(mat, bmp)
            mat.release()
            val ox = xs[i]
            val oy = ys[i] - minY
            val overlapEnd = if (i > 0) xs[i - 1] + w else ox
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                val base = (oy + y) * canvasW + ox
                for (x in 0 until w) {
                    val p = row[x]
                    if (p ushr 24 == 0) continue
                    val old = canvas[base + x]
                    val cx = ox + x
                    canvas[base + x] = if (old ushr 24 == 0 || cx >= overlapEnd) {
                        p
                    } else {
                        blend(old, p, (cx - ox).toFloat() / max(1, overlapEnd - ox))
                    }
                }
            }
        }
        bmp.recycle()

        val crop = PanoramaMath.cylinderCropRows(w, h, f)
        val top = (maxY - minY) + crop
        val bottom = h - crop
        val outH = bottom - top
        if (outH <= 0 || canvasW <= w) return null
        return Bitmap.createBitmap(canvasW, outH, Bitmap.Config.ARGB_8888).apply {
            setPixels(canvas, top * canvasW, canvasW, 0, 0, canvasW, outH)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val s = 1f - t
        fun c(shift: Int) = (((a shr shift) and 0xff) * s + ((b shr shift) and 0xff) * t).roundToInt()
        return (0xff shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    private fun offset(prev: Mat, curr: Mat, expected: Float, w: Int): Pair<Int, Int> {
        val e = expected.roundToInt().coerceIn(1, w - 1)
        val overlap = w - e
        if (overlap < w / 10) return e to 0
        val a = gray(prev.submat(Rect(e, 0, overlap, prev.rows())))
        val b = gray(curr.submat(Rect(0, 0, overlap, curr.rows())))
        val window = Mat()
        Imgproc.createHanningWindow(window, a.size(), CvType.CV_32F)
        val response = DoubleArray(1)
        val shift = Imgproc.phaseCorrelate(a, b, window, response)
        a.release(); b.release(); window.release()
        val dx = PanoramaMath.refine(e.toFloat(), shift.x, response[0], w).roundToInt()
        val dy = if (dx != e || response[0] > 0.05) (-shift.y).roundToInt().coerceIn(-w / 10, w / 10) else 0
        return dx to dy
    }

    private fun gray(rgba: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        g.convertTo(g, CvType.CV_32F)
        return g
    }

    private fun cylinderMaps(w: Int, h: Int, f: Float): Pair<Mat, Mat> {
        val mx = FloatArray(w * h)
        val my = FloatArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        for (x in 0 until w) {
            val theta = (x - cx) / f
            val sx = f * tan(theta) + cx
            val inv = 1f / cos(theta)
            for (y in 0 until h) {
                mx[y * w + x] = sx
                my[y * w + x] = (y - cy) * inv + cy
            }
        }
        val mapX = Mat(h, w, CvType.CV_32F).apply { put(0, 0, mx) }
        val mapY = Mat(h, w, CvType.CV_32F).apply { put(0, 0, my) }
        return mapX to mapY
    }

    /** Focal length in pixels for frames whose long side is [longSidePx]. */
    fun focalPx(focalMm: Float, sensorLongSideMm: Float, longSidePx: Int) =
        focalMm / sensorLongSideMm * longSidePx

}
