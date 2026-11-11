/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.util.Log
import dev.opencamera.ml.ImageTensors
import dev.opencamera.pipeline.OpenCv
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Backward displacement fields for face reshape and liquify, on a coarse grid; pure Kotlin so
 * caps and fields are unit-tested. A field value is the offset from an output pixel to the
 * source pixel it samples.
 */
object ReshapeMath {
    /** Hard limits: slim face 8 %, eyes at most 10 % larger (1 / (1 - 0.09)), slim nose 8 %. */
    const val SLIM_MAX = 0.08f
    const val EYES_MAX = 0.09f
    const val NOSE_MAX = 0.08f
    /** Liquify: bloat/pinch scale at most 30 %, push at most half the brush radius per stroke. */
    const val LIQUIFY_MAX = 0.3f

    class Field(val gw: Int, val gh: Int, val w: Int, val h: Int) {
        val dx = FloatArray(gw * gh)
        val dy = FloatArray(gw * gh)
        fun x(i: Int) = (i % gw) * (w - 1f) / (gw - 1)
        fun y(i: Int) = (i / gw) * (h - 1f) / (gh - 1)
        fun maxMagnitude(): Float = dx.indices.maxOfOrNull { hypot(dx[it], dy[it]) } ?: 0f

        /** Bilinear sample at pixel (px, py). */
        fun at(px: Float, py: Float): Pair<Float, Float> {
            val gx = (px / (w - 1f) * (gw - 1)).coerceIn(0f, gw - 1.001f)
            val gy = (py / (h - 1f) * (gh - 1)).coerceIn(0f, gh - 1.001f)
            val x0 = gx.toInt()
            val y0 = gy.toInt()
            val fx = gx - x0
            val fy = gy - y0
            fun s(a: FloatArray): Float {
                val i = y0 * gw + x0
                return (a[i] * (1 - fx) + a[i + 1] * fx) * (1 - fy) + (a[i + gw] * (1 - fx) + a[i + gw + 1] * fx) * fy
            }
            return s(dx) to s(dy)
        }

        companion object {
            fun forImage(w: Int, h: Int, cells: Int = 96): Field {
                val k = cells.toFloat() / max(w, h)
                return Field(max(2, (w * k).roundToInt() + 1), max(2, (h * k).roundToInt() + 1), w, h)
            }
        }
    }

    /** Forward point moves (from -> to) with zero-move anchors, interpolated by normalized Gaussians. */
    class Control(val x: Float, val y: Float, val mx: Float, val my: Float)

    fun interpolate(field: Field, controls: List<Control>, sigma: Float) {
        val s2 = 2f * sigma * sigma
        for (i in field.dx.indices) {
            val px = field.x(i)
            val py = field.y(i)
            var wsum = 0f
            var ax = 0f
            var ay = 0f
            for (c in controls) {
                // Backward map: weight by distance to the moved position.
                val ddx = px - (c.x + c.mx)
                val ddy = py - (c.y + c.my)
                val w = exp(-(ddx * ddx + ddy * ddy) / s2)
                wsum += w
                ax += w * c.mx
                ay += w * c.my
            }
            val norm = max(1f, wsum)
            field.dx[i] -= ax / norm
            field.dy[i] -= ay / norm
        }
    }

    /** Radial magnification around (cx, cy): factor up to 1 / (1 - s) at the centre. */
    fun bulge(field: Field, cx: Float, cy: Float, radius: Float, s: Float) {
        for (i in field.dx.indices) {
            val ox = field.x(i) - cx
            val oy = field.y(i) - cy
            val r = hypot(ox, oy) / radius
            if (r >= 1f) continue
            val k = s * (1f - r * r) * (1f - r * r)
            field.dx[i] -= ox * k
            field.dy[i] -= oy * k
        }
    }

    /** Liquify push along (vx, vy) pixels with a smooth falloff of [radius]. */
    fun push(field: Field, cx: Float, cy: Float, radius: Float, vx: Float, vy: Float) {
        for (i in field.dx.indices) {
            val r = hypot(field.x(i) - cx, field.y(i) - cy) / radius
            if (r >= 1f) continue
            val k = (1f - r * r) * (1f - r * r)
            field.dx[i] -= vx * k
            field.dy[i] -= vy * k
        }
    }

    fun clampStrength(key: String, v: Float): Float = when (key) {
        "slim" -> v.coerceIn(0f, 1f) * SLIM_MAX
        "eyes" -> v.coerceIn(0f, 1f) * EYES_MAX
        "nose" -> v.coerceIn(0f, 1f) * NOSE_MAX
        else -> v.coerceIn(0f, 1f) * LIQUIFY_MAX
    }

    /**
     * How much a straight line (x0,y0)-(x1,y1) bends under [field]: spread of the displacement
     * component perpendicular to the line, in pixels.
     */
    fun bend(field: Field, x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val len = hypot(x1 - x0, y1 - y0)
        if (len < 1f) return 0f
        val nx = -(y1 - y0) / len
        val ny = (x1 - x0) / len
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (k in 0..16) {
            val t = k / 16f
            val (dx, dy) = field.at(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            val p = dx * nx + dy * ny
            lo = min(lo, p)
            hi = max(hi, p)
        }
        return hi - lo
    }
}

/**
 * Face reshape (slim face, enlarge eyes, slim nose) by a landmark-driven displacement field, and
 * body liquify (push, bloat, pinch) with brush strokes. Off by default, never automatic, every
 * amount capped in [ReshapeMath]; edits are tagged [RetouchTags.RESHAPE] for metadata. Straight
 * background lines are protected: if the field bends a detected line by more than about a pixel,
 * the warp radius shrinks.
 */
class ReshapeTool(private val regions: FaceRegions) : RetouchTool {
    override val id = "reshape"
    override val input = InputKind.BRUSH
    override val defaultStrength = 0f

    override fun unavailableReason(): String? = if (!NativeRetouch.available) "native library missing" else null

    override fun tagsFor(params: RetouchParams): Set<String> {
        val any = params.strokes.isNotEmpty() || listOf("slim", "eyes", "nose").any { params.value(it) > 0f }
        return if (any) setOf(RetouchTags.RESHAPE) else emptySet()
    }

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        unavailableReason()?.let { throw RetouchUnavailable(it) }
        if (tagsFor(params).isEmpty()) throw RetouchUnavailable("nothing to change")
        val field = if (params.strokes.isNotEmpty()) liquify(image, params) else faceField(image, params)
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val code = NativeRetouch.warpGrid(image, out, field.dx, field.dy, field.gw, field.gh)
        if (code != NativeRetouch.OK) {
            out.recycle()
            throw RetouchUnavailable("native $code")
        }
        return RetouchResult(out, UndoPatch.whole(image), if (params.strokes.isNotEmpty()) "liquify" else "face", tags = tagsFor(params))
    }

    private fun faceField(image: Bitmap, params: RetouchParams): ReshapeMath.Field {
        val faces = regions.faces(image)
        val slim = ReshapeMath.clampStrength("slim", params.value("slim"))
        val eyes = ReshapeMath.clampStrength("eyes", params.value("eyes"))
        val nose = ReshapeMath.clampStrength("nose", params.value("nose"))
        val lines = cachedLines(image).filter { (a, b) -> faces.none { f -> near(f, (a.x + b.x) / 2, (a.y + b.y) / 2) } }
        var scale = 1f
        repeat(4) { attempt ->
            val field = ReshapeMath.Field.forImage(image.width, image.height)
            for (f in faces) build(field, f, slim, eyes, nose, scale)
            val worst = lines.maxOfOrNull { (a, b) -> ReshapeMath.bend(field, a.x, a.y, b.x, b.y) } ?: 0f
            if (worst <= MAX_BEND_PX * max(image.width, image.height) / 2048f || attempt == 3) {
                if (attempt > 0) Log.i(TAG, "Warp radius reduced to $scale to keep lines straight (bend $worst px)")
                return field
            }
            scale *= 0.75f
        }
        error("unreachable")
    }

    private fun build(field: ReshapeMath.Field, f: FaceGeometry, slim: Float, eyes: Float, nose: Float, scale: Float) {
        val lm = f.landmarks
        val axisX = (lm[10].x + lm[152].x) / 2f
        val controls = ArrayList<ReshapeMath.Control>()
        if (slim > 0f) {
            for (i in JAW) {
                val p = lm[i]
                controls += ReshapeMath.Control(p.x, p.y, (axisX - p.x) * slim, 0f)
            }
        }
        if (nose > 0f) {
            val noseAxis = lm[NOSE_TIP].x
            for (i in NOSE_WINGS) {
                val p = lm[i]
                controls += ReshapeMath.Control(p.x, p.y, (noseAxis - p.x) * nose * 1.5f, 0f)
            }
        }
        if (controls.isNotEmpty()) {
            for (i in ANCHORS) controls += ReshapeMath.Control(lm[i].x, lm[i].y, 0f, 0f)
            ReshapeMath.interpolate(field, controls, f.width * 0.12f * scale)
        }
        if (eyes > 0f) {
            for ((c, r) in f.irises()) ReshapeMath.bulge(field, c.x, c.y, max(r * 3.2f, f.width * 0.08f) * scale, eyes)
        }
    }

    private fun liquify(image: Bitmap, params: RetouchParams): ReshapeMath.Field {
        val field = ReshapeMath.Field.forImage(image.width, image.height, cells = 128)
        val long = max(image.width, image.height)
        val mode = params.value("mode").roundToInt()
        val s = ReshapeMath.clampStrength("liquify", params.strength)
        for (stroke in params.strokes) {
            val radius = stroke.radius * long
            val pts = stroke.points.map { NPoint(it.x * image.width, it.y * image.height) }
            when (mode) {
                MODE_PUSH -> {
                    if (pts.size < 2) continue
                    val vx = pts.last().x - pts.first().x
                    val vy = pts.last().y - pts.first().y
                    val len = hypot(vx, vy)
                    val cap = radius * 0.5f * s / ReshapeMath.LIQUIFY_MAX
                    val k = if (len > cap) cap / len else 1f
                    ReshapeMath.push(field, pts.first().x, pts.first().y, radius, vx * k, vy * k)
                }
                MODE_BLOAT -> ReshapeMath.bulge(field, pts.first().x, pts.first().y, radius, s)
                else -> ReshapeMath.bulge(field, pts.first().x, pts.first().y, radius, -s)
            }
        }
        return field
    }

    private var linesKey: Triple<Int, Int, Int>? = null
    private var linesCache: List<Pair<NPoint, NPoint>> = emptyList()

    /** Lines of the unwarped photo; reused while a slider is dragged over the same image. */
    @Synchronized
    private fun cachedLines(image: Bitmap): List<Pair<NPoint, NPoint>> {
        val key = Triple(image.width, image.height, image.getPixel(image.width / 3, image.height / 3) xor image.getPixel(image.width * 2 / 3, image.height / 2))
        if (key != linesKey) {
            linesCache = backgroundLines(image)
            linesKey = key
        }
        return linesCache
    }

    private fun near(f: FaceGeometry, x: Float, y: Float): Boolean {
        val cx = f.landmarks.map { it.x }.average().toFloat()
        val cy = f.landmarks.map { it.y }.average().toFloat()
        return hypot(x - cx, y - cy) < f.width * 1.2f
    }

    /** Long straight lines (image pixels) for the background check; empty without OpenCV. */
    private fun backgroundLines(image: Bitmap): List<Pair<NPoint, NPoint>> {
        if (!OpenCv.available) return emptyList()
        return runCatching {
            val k = 640f / max(image.width, image.height)
            val small = ImageTensors.scaled(image, (image.width * k).roundToInt(), (image.height * k).roundToInt())
            val rgba = Mat()
            val gray = Mat()
            val edges = Mat()
            val lines = Mat()
            try {
                Utils.bitmapToMat(small, rgba)
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.Canny(gray, edges, 60.0, 160.0)
                Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 60, 80.0, 4.0)
                (0 until lines.rows()).map { r ->
                    val l = lines.get(r, 0)
                    NPoint((l[0] / k).toFloat(), (l[1] / k).toFloat()) to NPoint((l[2] / k).toFloat(), (l[3] / k).toFloat())
                }.filter { (a, b) -> abs(a.x - b.x) + abs(a.y - b.y) > 0 }
            } finally {
                if (small !== image) small.recycle()
                rgba.release()
                gray.release()
                edges.release()
                lines.release()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "ReshapeTool"
        const val MODE_PUSH = 0
        const val MODE_BLOAT = 1
        const val MODE_PINCH = 2
        /** Allowed bend of a background line, in pixels at 2048 px long side. */
        private const val MAX_BEND_PX = 1.2f

        // Lower face oval (cheeks and jaw) of the 478-point mesh.
        private val JAW = intArrayOf(234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454)
        private val NOSE_WINGS = intArrayOf(48, 115, 220, 278, 344, 440)
        private const val NOSE_TIP = 1
        // Features that must not move with the jaw: eyes, brows, nose bridge, mouth corners, chin, forehead.
        private val ANCHORS = intArrayOf(33, 133, 362, 263, 105, 334, 6, 1, 61, 291, 152, 10, 168)
    }
}
