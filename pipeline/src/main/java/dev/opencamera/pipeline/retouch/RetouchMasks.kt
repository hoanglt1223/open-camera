/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Binary hole mask over a w x h image (1 = selected), with its bounding box. */
class HoleMask(val width: Int, val height: Int, val data: ByteArray) {
    val bounds: Rect by lazy {
        var x0 = width
        var y0 = height
        var x1 = -1
        var y1 = -1
        for (y in 0 until height) {
            val o = y * width
            for (x in 0 until width) {
                if (data[o + x].toInt() == 0) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) Rect() else Rect(x0, y0, x1 + 1, y1 + 1)
    }

    val area: Int by lazy { data.count { it.toInt() != 0 } }
    val isEmpty get() = bounds.isEmpty

    /** Square dilation by [r] pixels (separable max). */
    fun dilated(r: Int): HoleMask {
        if (r <= 0) return this
        val tmp = ByteArray(data.size)
        for (y in 0 until height) {
            val o = y * width
            var last = -1_000_000
            for (x in 0 until width) {
                if (data[o + x].toInt() != 0) last = x
                if (x - last <= r) tmp[o + x] = 1
            }
            last = 1_000_000
            for (x in width - 1 downTo 0) {
                if (data[o + x].toInt() != 0) last = x
                if (last - x <= r) tmp[o + x] = 1
            }
        }
        val out = ByteArray(data.size)
        for (x in 0 until width) {
            var last = -1_000_000
            for (y in 0 until height) {
                if (tmp[y * width + x].toInt() != 0) last = y
                if (y - last <= r) out[y * width + x] = 1
            }
            last = 1_000_000
            for (y in height - 1 downTo 0) {
                if (tmp[y * width + x].toInt() != 0) last = y
                if (last - y <= r) out[y * width + x] = 1
            }
        }
        return HoleMask(width, height, out)
    }

    /** The part inside [r] as a new mask of r's size. */
    fun crop(r: Rect): HoleMask {
        val out = ByteArray(r.width() * r.height())
        for (y in 0 until r.height()) System.arraycopy(data, (y + r.top) * width + r.left, out, y * r.width(), r.width())
        return HoleMask(r.width(), r.height(), out)
    }

    fun union(other: HoleMask): HoleMask {
        require(other.width == width && other.height == height)
        return HoleMask(width, height, ByteArray(data.size) { if (data[it].toInt() != 0 || other.data[it].toInt() != 0) 1 else 0 })
    }

    companion object {
        /** Rasterizes normalized strokes at w x h; erase strokes clear. */
        fun fromStrokes(strokes: List<Stroke>, w: Int, h: Int): HoleMask {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            try {
                val canvas = Canvas(bmp)
                val long = max(w, h).toFloat()
                for (s in strokes) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = max(1f, s.radius * long * 2f)
                        if (s.erase) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    }
                    if (s.points.size == 1) {
                        paint.style = Paint.Style.FILL
                        canvas.drawCircle(s.points[0].x * w, s.points[0].y * h, paint.strokeWidth / 2f, paint)
                        continue
                    }
                    val path = Path()
                    s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
                    canvas.drawPath(path, paint)
                }
                return fromAlpha(bmp)
            } finally {
                bmp.recycle()
            }
        }

        fun fromAlpha(bmp: Bitmap, threshold: Int = 96): HoleMask {
            val w = bmp.width
            val h = bmp.height
            val buf = ByteBuffer.allocate(bmp.rowBytes * h)
            bmp.copyPixelsToBuffer(buf)
            val raw = buf.array()
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                if ((raw[y * bmp.rowBytes + x].toInt() and 0xff) >= threshold) out[y * w + x] = 1
            }
            return HoleMask(w, h, out)
        }

        /** Thresholds a soft mask (mw x mh) resampled to w x h. */
        fun fromSoft(soft: FloatArray, mw: Int, mh: Int, w: Int, h: Int, threshold: Float = 0.5f): HoleMask {
            val out = ByteArray(w * h)
            for (y in 0 until h) {
                val sy = min(mh - 1, (y * mh) / h)
                for (x in 0 until w) {
                    val sx = min(mw - 1, (x * mw) / w)
                    if (soft[sy * mw + sx] >= threshold) out[y * w + x] = 1
                }
            }
            return HoleMask(w, h, out)
        }
    }
}

/** A soft mask of a face region: [values] (mw x mh) over [rect] in image pixels. */
class RegionMask(val rect: RectF, val mw: Int, val mh: Int, val values: FloatArray) {
    val rectArray get() = floatArrayOf(rect.left, rect.top, rect.width(), rect.height())
    val pixelRect get() = Rect(rect.left.toInt(), rect.top.toInt(), (rect.right + 1).roundToInt(), (rect.bottom + 1).roundToInt())

    companion object {
        /**
         * Rasterizes [polygons] (image pixels) into a mask of their joint bounding box padded by
         * [pad] px, at most [maxSide] values on the long side, feathered by [feather] (fraction).
         */
        fun of(polygons: List<List<NPoint>>, pad: Float, maxSide: Int = 256, feather: Float = 0.04f, holes: List<List<NPoint>> = emptyList()): RegionMask? {
            val pts = polygons.flatten()
            if (pts.size < 3) return null
            val rect = RectF(pts.minOf { it.x } - pad, pts.minOf { it.y } - pad, pts.maxOf { it.x } + pad, pts.maxOf { it.y } + pad)
            if (rect.width() < 2f || rect.height() < 2f) return null
            val k = maxSide / max(rect.width(), rect.height())
            val mw = max(4, (rect.width() * k).roundToInt())
            val mh = max(4, (rect.height() * k).roundToInt())
            val bmp = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
            try {
                val canvas = Canvas(bmp)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
                val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                fun path(poly: List<NPoint>) = Path().apply {
                    poly.forEachIndexed { i, p ->
                        val x = (p.x - rect.left) * mw / rect.width()
                        val y = (p.y - rect.top) * mh / rect.height()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                polygons.forEach { canvas.drawPath(path(it), fill) }
                holes.forEach { canvas.drawPath(path(it), clear) }
                val buf = ByteBuffer.allocate(bmp.rowBytes * mh)
                bmp.copyPixelsToBuffer(buf)
                val raw = buf.array()
                var values = FloatArray(mw * mh) { i -> (raw[(i / mw) * bmp.rowBytes + i % mw].toInt() and 0xff) / 255f }
                val r = max(1, (max(mw, mh) * feather).roundToInt())
                values = MaskMath.boxBlur(values, mw, mh, r)
                return RegionMask(rect, mw, mh, values)
            } finally {
                bmp.recycle()
            }
        }
    }
}

/** Pure helpers shared by the tools (no Android types, unit-tested). */
object MaskMath {
    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val k = 1f / (2 * r + 1)
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += src[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc * k
                acc += src[y * w + min(x + r + 1, w - 1)] - src[y * w + max(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc * k
                acc += tmp[min(y + r + 1, h - 1) * w + x] - tmp[max(y - r, 0) * w + x]
            }
        }
        return out
    }

    /** Keeps the 4-connected component of [mask] (values >= 0.5) containing (sx, sy); empty if none. */
    fun component(mask: FloatArray, w: Int, h: Int, sx: Int, sy: Int, threshold: Float = 0.5f): FloatArray {
        val out = FloatArray(mask.size)
        if (sx !in 0 until w || sy !in 0 until h || mask[sy * w + sx] < threshold) return out
        val stack = ArrayDeque<Int>()
        stack.addLast(sy * w + sx)
        out[sy * w + sx] = 1f
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                if (out[j] == 0f && mask[j] >= threshold) {
                    out[j] = 1f
                    stack.addLast(j)
                }
            }
        }
        return out
    }

    /**
     * Region growing from (sx, sy) over packed RGB [px] (w x h): accepts neighbours whose colour
     * is within [tolerance] (Euclidean, 0..441) of the running mean, up to [maxFraction] of the image.
     */
    fun regionGrow(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tolerance: Float, maxFraction: Float): FloatArray {
        val out = FloatArray(w * h)
        if (sx !in 0 until w || sy !in 0 until h) return out
        val limit = (w * h * maxFraction).toInt()
        var mr = 0.0
        var mg = 0.0
        var mb = 0.0
        var n = 0
        val queue = ArrayDeque<Int>()
        fun add(i: Int) {
            out[i] = 1f
            val c = px[i]
            mr += (c shr 16 and 0xff)
            mg += (c shr 8 and 0xff)
            mb += (c and 0xff)
            n++
            queue.addLast(i)
        }
        add(sy * w + sx)
        val t2 = tolerance * tolerance
        while (queue.isNotEmpty() && n < limit) {
            val i = queue.removeFirst()
            val x = i % w
            val y = i / w
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                if (out[j] != 0f) continue
                val c = px[j]
                val dr = (c shr 16 and 0xff) - mr / n
                val dg = (c shr 8 and 0xff) - mg / n
                val db = (c and 0xff) - mb / n
                if (dr * dr + dg * dg + db * db <= t2) add(j)
            }
        }
        return out
    }
}
