/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import dev.opencamera.ml.ImageTensors
import dev.opencamera.pipeline.OpenCv
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Line, horizon and keystone geometry; pure (unit-tested). Lines are (x0, y0, x1, y1) in pixels. */
object GeometryMath {
    /** Lines within this many degrees of an axis count for levelling. */
    const val MAX_TILT = 12f
    /** Keystone: a parameter of 1 insets the far edge by this fraction of the width/height. */
    const val MAX_INSET = 0.3f
    private const val AGREEMENT = 1.5f
    private const val MIN_AGREEMENT = 0.5f

    fun angleDeg(l: FloatArray) = Math.toDegrees(atan2((l[3] - l[1]).toDouble(), (l[2] - l[0]).toDouble())).toFloat()
    fun length(l: FloatArray) = hypot(l[2] - l[0], l[3] - l[1])

    /** Signed deviation from the nearest axis in (-45, 45]; positive = clockwise on screen. */
    fun deviation(angle: Float): Float {
        var a = angle % 90f
        if (a > 45f) a -= 90f
        if (a <= -45f) a += 90f
        return a
    }

    /** Length-weighted median tilt of near-axis lines, or null when there is too little evidence. */
    fun tilt(lines: List<FloatArray>, minTotalLength: Float): Float? {
        val devs = lines.map { deviation(angleDeg(it)) to length(it) }.filter { abs(it.first) <= MAX_TILT }
        val total = devs.sumOf { it.second.toDouble() }.toFloat()
        if (total < minTotalLength) return null
        var acc = 0f
        var median: Float? = null
        for ((d, w) in devs.sortedBy { it.first }) {
            acc += w
            if (acc >= total / 2) {
                median = d
                break
            }
        }
        val m = median ?: return null
        // Levelling needs agreement: most of the line length must share the tilt (a flag,
        // a roof or a diagonal pattern alone must not rotate the photo).
        val agreeing = devs.filter { abs(it.first - m) <= AGREEMENT }.sumOf { it.second.toDouble() }.toFloat()
        return if (agreeing >= total * MIN_AGREEMENT) m else null
    }

    /** Least-squares intersection of lines (homogeneous), null when they are near parallel. */
    fun vanishingPoint(lines: List<FloatArray>): Pair<Float, Float>? {
        var a11 = 0.0; var a12 = 0.0; var a22 = 0.0; var b1 = 0.0; var b2 = 0.0
        for (l in lines) {
            val len = length(l).toDouble()
            if (len < 1) continue
            val nx = -(l[3] - l[1]) / len
            val ny = (l[2] - l[0]) / len
            val c = nx * l[0] + ny * l[1]
            a11 += len * nx * nx; a12 += len * nx * ny; a22 += len * ny * ny
            b1 += len * nx * c; b2 += len * ny * c
        }
        val det = a11 * a22 - a12 * a12
        val scale = (a11 + a22) * (a11 + a22)
        if (scale <= 0 || det / scale < 1e-5) return null
        return ((a22 * b1 - a12 * b2) / det).toFloat() to ((a11 * b2 - a12 * b1) / det).toFloat()
    }

    /**
     * Vertical keystone parameter in [-1, 1] that makes near-vertical [lines] parallel: positive
     * when they converge upwards (camera tilted up). 0 when there is no reliable vanishing point.
     */
    fun verticalKeystone(lines: List<FloatArray>, w: Int, h: Int): Float {
        val vert = lines.filter { abs(abs(angleDeg(it)) - 90f) < 25f }
        if (vert.size < 2) return 0f
        val (_, vy) = vanishingPoint(vert) ?: return 0f
        return keystoneFrom(vy, h.toFloat())
    }

    fun horizontalKeystone(lines: List<FloatArray>, w: Int, h: Int): Float {
        val hor = lines.filter { abs(deviation(angleDeg(it))) < 25f && abs(abs(angleDeg(it)) - 90f) >= 25f }
        if (hor.size < 2) return 0f
        val (vx, _) = vanishingPoint(hor) ?: return 0f
        // Same construction rotated: lines converging to the left are like vertical lines converging up.
        return keystoneFrom(vx, w.toFloat())
    }

    /**
     * Inset of the far edge for a vanishing point at coordinate [v] along an axis of length [n]
     * (v < 0: before the near edge's opposite side). Lines from the near corners to the point
     * cross the far edge at an inset of t / 2 with t = n / (n - v).
     */
    private fun keystoneFrom(v: Float, n: Float): Float {
        val t = when {
            v < 0f -> n / (n - v)
            v > n -> -n / v
            else -> return 0f
        }
        val inset = t / 2f
        if (abs(inset) < 0.005f) return 0f
        return (inset / MAX_INSET).coerceIn(-1f, 1f)
    }

    /**
     * Source quad (TL, TR, BR, BL as x,y pairs) whose content is stretched to the full frame:
     * vertical > 0 narrows the top edge, horizontal > 0 narrows the left edge.
     */
    fun keystoneQuad(vertical: Float, horizontal: Float, w: Int, h: Int): FloatArray {
        val v = vertical.coerceIn(-1f, 1f) * MAX_INSET
        val hz = horizontal.coerceIn(-1f, 1f) * MAX_INSET
        val W = w.toFloat()
        val H = h.toFloat()
        val q = floatArrayOf(
            max(0f, v) * W, max(0f, hz) * H,
            W - max(0f, v) * W, max(0f, -hz) * H,
            W - max(0f, -v) * W, H - max(0f, -hz) * H,
            max(0f, -v) * W, H - max(0f, hz) * H,
        )
        return q
    }

    /** 3x3 homography (row-major) mapping 4 [src] points to 4 [dst] points. */
    fun homography(src: FloatArray, dst: FloatArray): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[2 * i].toDouble(); val y = src[2 * i + 1].toDouble()
            val u = dst[2 * i].toDouble(); val v = dst[2 * i + 1].toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            val p = (c until 8).maxBy { abs(a[it][c]) }
            val t = a[c]; a[c] = a[p]; a[p] = t
            require(abs(a[c][c]) > 1e-12) { "degenerate quad" }
            for (r in 0 until 8) {
                if (r == c) continue
                val f = a[r][c] / a[c][c]
                for (k in c until 9) a[r][k] -= f * a[c][k]
            }
        }
        return DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] }
    }

    fun rotation(deg: Float, cx: Float, cy: Float): DoubleArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r); val s = sin(r)
        return doubleArrayOf(c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy, 0.0, 0.0, 1.0)
    }

    fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun map(m: DoubleArray, x: Float, y: Float): Pair<Float, Float> {
        val w = m[6] * x + m[7] * y + m[8]
        return ((m[0] * x + m[1] * y + m[2]) / w).toFloat() to ((m[3] * x + m[4] * y + m[5]) / w).toFloat()
    }

    /**
     * Largest rectangle with the aspect of w x h, centred on [cx], [cy], inside the convex
     * quad [q] (TL, TR, BR, BL). Returns (left, top, width, height).
     */
    fun inscribed(q: FloatArray, w: Int, h: Int, cx: Float, cy: Float): FloatArray {
        fun inside(x: Float, y: Float): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val x0 = q[2 * i]; val y0 = q[2 * i + 1]
                val x1 = q[(2 * i + 2) % 8]; val y1 = q[(2 * i + 3) % 8]
                val cross = (x1 - x0) * (y - y0) - (y1 - y0) * (x - x0)
                val s = if (cross > 1e-3f) 1 else if (cross < -1e-3f) -1 else 0
                if (s != 0) {
                    if (sign == 0) sign = s else if (s != sign) return false
                }
            }
            return true
        }
        var lo = 0f
        var hi = 4f
        repeat(30) {
            val s = (lo + hi) / 2
            val hw = w * s / 2; val hh = h * s / 2
            if (inside(cx - hw, cy - hh) && inside(cx + hw, cy - hh) && inside(cx + hw, cy + hh) && inside(cx - hw, cy + hh)) lo = s else hi = s
        }
        return floatArrayOf(cx - w * lo / 2, cy - h * lo / 2, w * lo, h * lo)
    }

    /** Full transform: keystone, then rotation, then crop to the inscribed rectangle. */
    fun transform(w: Int, h: Int, angle: Float, vertical: Float, horizontal: Float): Pair<DoubleArray, FloatArray> {
        val frame = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val persp = homography(keystoneQuad(vertical, horizontal, w, h), frame)
        val m = multiply(rotation(angle, w / 2f, h / 2f), persp)
        val quad = FloatArray(8)
        for (i in 0 until 4) {
            val (x, y) = map(m, frame[2 * i], frame[2 * i + 1])
            quad[2 * i] = x; quad[2 * i + 1] = y
        }
        val (cx, cy) = map(m, w / 2f, h / 2f)
        val rect = inscribed(quad, w, h, cx, cy)
        val shift = doubleArrayOf(1.0, 0.0, -rect[0].toDouble(), 0.0, 1.0, -rect[1].toDouble(), 0.0, 0.0, 1.0)
        return multiply(shift, m) to rect
    }
}

/**
 * Straighten (auto horizon / manual angle), perspective (auto from vanishing points / manual
 * keystone) and lens distortion (Camera2 LENS_DISTORTION + intrinsics recorded for the camera,
 * or a manual k1). Output is cropped to the largest upright rectangle, never padded.
 */
class GeometryTool : RetouchTool {
    override val id = "geometry"
    override val input = InputKind.NONE
    override val defaultStrength = 1f

    override fun unavailableReason(): String? = if (!OpenCv.available) "OpenCV unavailable" else null

    override fun methods() = listOf(
        RetouchMethod(LEVEL, OpenCv.available),
        RetouchMethod(PERSPECTIVE, OpenCv.available),
        RetouchMethod(BOTH, OpenCv.available),
    )

    data class Estimate(val angle: Float, val vertical: Float, val horizontal: Float)

    /** Line-based estimate on a 1024 px copy: correction angle and keystone parameters. */
    fun estimate(image: Bitmap): Estimate {
        val lines = lines(image)
        val tilt = GeometryMath.tilt(lines, minTotalLength = max(image.width, image.height) * 0.5f) ?: 0f
        return Estimate(-tilt, GeometryMath.verticalKeystone(lines, image.width, image.height), GeometryMath.horizontalKeystone(lines, image.width, image.height))
    }

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        unavailableReason()?.let { throw RetouchUnavailable(it) }
        var values = params.values
        if (params.method != null && params.method != MANUAL) {
            val e = estimate(image)
            val auto = HashMap<String, Float>()
            if (params.method == LEVEL || params.method == BOTH) auto["angle"] = e.angle
            if (params.method == PERSPECTIVE || params.method == BOTH) {
                auto["vertical"] = e.vertical
                auto["horizontal"] = e.horizontal
            }
            values = values + auto
        }
        val angle = (values["angle"] ?: 0f).coerceIn(-45f, 45f)
        val vertical = (values["vertical"] ?: 0f).coerceIn(-1f, 1f)
        val horizontal = (values["horizontal"] ?: 0f).coerceIn(-1f, 1f)
        var src = image
        if ((values["k1"] ?: 0f) != 0f || (values["k2"] ?: 0f) != 0f) src = undistort(image, values)
        val (m, rect) = GeometryMath.transform(src.width, src.height, angle, vertical, horizontal)
        val ow = rect[2].roundToInt().coerceAtLeast(16)
        val oh = rect[3].roundToInt().coerceAtLeast(16)
        val out = warp(src, m, ow, oh)
        if (src !== image) src.recycle()
        val resolved = params.copy(method = MANUAL, values = values)
        return RetouchResult(out, UndoPatch.whole(image), params.method ?: MANUAL, note = "%.1f deg".format(java.util.Locale.US, angle), resolved = resolved)
    }

    private fun warp(src: Bitmap, m: DoubleArray, ow: Int, oh: Int): Bitmap {
        val a = Mat()
        val b = Mat()
        val h = Mat(3, 3, CvType.CV_64F)
        try {
            Utils.bitmapToMat(src, a)
            h.put(0, 0, *m)
            Imgproc.warpPerspective(a, b, h, Size(ow.toDouble(), oh.toDouble()), Imgproc.INTER_LINEAR)
            return Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(b, it) }
        } finally {
            a.release(); b.release(); h.release()
        }
    }

    /**
     * Removes radial/tangential distortion. Values are normalized: fx, fy, cx, cy relative to the
     * image width/height (Camera2 intrinsics divided by the active array size); k1..k3, p1, p2 as
     * in Camera2 LENS_DISTORTION. The undistorted frame is cropped to valid pixels.
     */
    private fun undistort(image: Bitmap, v: Map<String, Float>): Bitmap {
        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val long = max(w, h)
        val fx = (v["fx"]?.times(w)) ?: long
        val fy = (v["fy"]?.times(h)) ?: long
        val k = Mat(3, 3, CvType.CV_64F)
        val newK = Mat()
        val dist = MatOfDouble(
            (v["k1"] ?: 0f).toDouble(), (v["k2"] ?: 0f).toDouble(),
            (v["p1"] ?: 0f).toDouble(), (v["p2"] ?: 0f).toDouble(), (v["k3"] ?: 0f).toDouble(),
        )
        val a = Mat()
        val b = Mat()
        try {
            k.put(0, 0, fx, 0.0, (v["cx"] ?: 0.5f) * w, 0.0, fy, (v["cy"] ?: 0.5f) * h, 0.0, 0.0, 1.0)
            val size = Size(w, h)
            Calib3d.getOptimalNewCameraMatrix(k, dist, size, 0.0, size).copyTo(newK)
            Utils.bitmapToMat(image, a)
            Calib3d.undistort(a, b, k, dist, newK)
            return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(b, it) }
        } finally {
            k.release(); newK.release(); dist.release(); a.release(); b.release()
        }
    }

    private fun lines(image: Bitmap): List<FloatArray> {
        val k = min(1f, 1024f / max(image.width, image.height))
        val small = ImageTensors.scaled(image, (image.width * k).roundToInt(), (image.height * k).roundToInt())
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        val lines = Mat()
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            val minLen = max(small.width, small.height) * 0.08
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 360, 50, minLen, minLen * 0.1)
            return (0 until lines.rows()).map { r ->
                val l = lines.get(r, 0)
                floatArrayOf((l[0] / k).toFloat(), (l[1] / k).toFloat(), (l[2] / k).toFloat(), (l[3] / k).toFloat())
            }
        } finally {
            if (small !== image) small.recycle()
            rgba.release(); gray.release(); edges.release(); lines.release()
        }
    }

    companion object {
        const val MANUAL = "manual"
        const val LEVEL = "level"
        const val PERSPECTIVE = "perspective"
        const val BOTH = "level+perspective"

    }
}
