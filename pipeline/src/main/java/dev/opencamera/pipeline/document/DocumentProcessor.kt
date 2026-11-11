/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.OutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Corner order helpers for a 4-point document outline (x0,y0..x3,y3). */
object QuadMath {
    /** Orders corners as top-left, top-right, bottom-right, bottom-left. */
    fun order(points: FloatArray): FloatArray {
        require(points.size == 8)
        val idx = (0..3).toList()
        val sum = idx.map { points[it * 2] + points[it * 2 + 1] }
        val diff = idx.map { points[it * 2 + 1] - points[it * 2] }
        val tl = idx.minBy { sum[it] }
        val br = idx.maxBy { sum[it] }
        val tr = idx.filter { it != tl && it != br }.minBy { diff[it] }
        val bl = idx.first { it != tl && it != br && it != tr }
        return floatArrayOf(
            points[tl * 2], points[tl * 2 + 1], points[tr * 2], points[tr * 2 + 1],
            points[br * 2], points[br * 2 + 1], points[bl * 2], points[bl * 2 + 1],
        )
    }

    /** Output size (width, height) of the rectified page for an ordered quad. */
    fun targetSize(q: FloatArray): Pair<Int, Int> {
        fun d(a: Int, b: Int) = hypot(q[a * 2] - q[b * 2], q[a * 2 + 1] - q[b * 2 + 1])
        val w = max(d(0, 1), d(3, 2)).roundToInt().coerceAtLeast(1)
        val h = max(d(0, 3), d(1, 2)).roundToInt().coerceAtLeast(1)
        return w to h
    }

    /** Polygon area (shoelace), for rejecting tiny detections. */
    fun area(q: FloatArray): Float {
        var a = 0f
        for (i in 0..3) {
            val j = (i + 1) % 4
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return kotlin.math.abs(a) / 2f
    }
}

/**
 * Document mode: finds the page outline, rectifies it and whitens the background. OpenCV must be
 * loaded (see [dev.opencamera.pipeline.OpenCv]).
 */
object DocumentProcessor {
    enum class Enhance { COLOR, GRAY, BLACK_WHITE }

    private const val DETECT_SIZE = 640.0

    /** Ordered page corners in [bitmap] pixels, or null when no page-like outline is found. */
    fun detect(bitmap: Bitmap): FloatArray? {
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            val scale = DETECT_SIZE / max(bitmap.width, bitmap.height)
            Imgproc.resize(rgba, rgba, Size(bitmap.width * scale, bitmap.height * scale))
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val minArea = edges.rows() * edges.cols() * 0.15
            for (c in contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)) {
                if (Imgproc.contourArea(c) < minArea) break
                val c2f = MatOfPoint2f(*c.toArray())
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * Imgproc.arcLength(c2f, true), true)
                val pts = approx.toArray()
                if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                    val raw = FloatArray(8)
                    pts.forEachIndexed { i, p ->
                        raw[i * 2] = (p.x / scale).toFloat()
                        raw[i * 2 + 1] = (p.y / scale).toFloat()
                    }
                    return QuadMath.order(raw)
                }
            }
            return null
        } finally {
            rgba.release(); gray.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Rectifies [bitmap] to the ordered [quad] (or keeps the full frame) and enhances it. */
    fun process(bitmap: Bitmap, quad: FloatArray?, enhance: Enhance): Bitmap {
        val src = Mat()
        val warped = Mat()
        try {
            Utils.bitmapToMat(bitmap, src)
            if (quad != null) {
                val (w, h) = QuadMath.targetSize(quad)
                val from = MatOfPoint2f(*Array(4) { Point(quad[it * 2].toDouble(), quad[it * 2 + 1].toDouble()) })
                val to = MatOfPoint2f(
                    Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0),
                )
                val m = Imgproc.getPerspectiveTransform(from, to)
                Imgproc.warpPerspective(src, warped, m, Size(w.toDouble(), h.toDouble()))
                m.release()
            } else {
                src.copyTo(warped)
            }
            val out = enhance(warped, enhance)
            val result = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, result)
            if (out !== warped) out.release()
            return result
        } finally {
            src.release(); warped.release()
        }
    }

    /** Divides out the illumination (a large blur of the page) so paper turns white. */
    private fun enhance(rgba: Mat, mode: Enhance): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val k = (max(rgb.cols(), rgb.rows()) / 20) or 1
        val background = Mat()
        Imgproc.morphologyEx(
            rgb, background, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0)),
        )
        Imgproc.GaussianBlur(background, background, Size(k.toDouble(), k.toDouble()), 0.0)
        val normalized = Mat()
        Core.divide(rgb, background, normalized, 255.0)
        background.release()
        rgb.release()
        val out = Mat()
        when (mode) {
            Enhance.COLOR -> Imgproc.cvtColor(normalized, out, Imgproc.COLOR_RGB2RGBA)
            Enhance.GRAY -> {
                val g = Mat()
                Imgproc.cvtColor(normalized, g, Imgproc.COLOR_RGB2GRAY)
                g.convertTo(g, CvType.CV_8U, 1.2, -30.0)
                Imgproc.cvtColor(g, out, Imgproc.COLOR_GRAY2RGBA)
                g.release()
            }
            Enhance.BLACK_WHITE -> {
                val g = Mat()
                Imgproc.cvtColor(normalized, g, Imgproc.COLOR_RGB2GRAY)
                Imgproc.adaptiveThreshold(
                    g, g, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 25, 12.0,
                )
                Imgproc.cvtColor(g, out, Imgproc.COLOR_GRAY2RGBA)
                g.release()
            }
        }
        normalized.release()
        return out
    }
}

/** Writes pages to a PDF, one image per A4 page, fitted with a margin. */
object PdfExporter {
    private const val A4_W = 595
    private const val A4_H = 842
    private const val MARGIN = 18

    fun write(pages: List<Bitmap>, out: OutputStream) {
        require(pages.isNotEmpty()) { "no pages" }
        val doc = PdfDocument()
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            pages.forEachIndexed { i, bmp ->
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) A4_H else A4_W
                val ph = if (landscape) A4_W else A4_H
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val s = minOf((pw - 2f * MARGIN) / bmp.width, (ph - 2f * MARGIN) / bmp.height)
                val w = (bmp.width * s).roundToInt()
                val h = (bmp.height * s).roundToInt()
                val left = (pw - w) / 2
                val top = (ph - h) / 2
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, null, Rect(left, top, left + w, top + h), paint)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Page size used for a bitmap, exposed for tests and previews. */
    fun pageSizeFor(width: Int, height: Int) = if (width > height) A4_H to A4_W else A4_W to A4_H
}
