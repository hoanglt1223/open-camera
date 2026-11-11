/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import android.graphics.Bitmap
import android.os.SystemClock

/**
 * Splits large images into fixed-size model windows. Windows overlap by [margin] pixels; each
 * window contributes only its core (the window minus the margin, except at image borders), so
 * neighbouring results meet without seams and no full-image float buffer is ever allocated.
 */
object Tiling {
    /** [x], [y]: window origin in the source. [coreX]..: the part of the window kept in the output. */
    data class Tile(val x: Int, val y: Int, val coreX: Int, val coreY: Int, val coreW: Int, val coreH: Int)

    fun plan(width: Int, height: Int, tile: Int, margin: Int): List<Tile> {
        require(tile > 2 * margin) { "Tile $tile too small for margin $margin" }
        val xs = starts(width, tile, margin)
        val ys = starts(height, tile, margin)
        val out = ArrayList<Tile>(xs.size * ys.size)
        for ((yi, y) in ys.withIndex()) {
            val (cy0, cy1) = core(ys, yi, y, tile, height)
            for ((xi, x) in xs.withIndex()) {
                val (cx0, cx1) = core(xs, xi, x, tile, width)
                out += Tile(x, y, cx0, cy0, cx1 - cx0, cy1 - cy0)
            }
        }
        return out
    }

    /** Window origins along one axis; the last window is shifted inward to stay inside. */
    private fun starts(size: Int, tile: Int, margin: Int): List<Int> {
        if (size <= tile) return listOf(0)
        val step = tile - 2 * margin
        val s = ArrayList<Int>()
        var p = 0
        while (p + tile < size) {
            s += p
            p += step
        }
        s += size - tile
        return s.distinct()
    }

    /** Core span [from, to) in source coordinates; adjacent cores tile the axis exactly. */
    private fun core(starts: List<Int>, i: Int, start: Int, tile: Int, size: Int): Pair<Int, Int> {
        // Neighbouring windows split their overlap in the middle, which is at least
        // [margin] away from either window's edge because windows step by tile - 2 * margin.
        val from = if (i == 0) 0 else (start + starts[i - 1] + tile) / 2
        val to = if (i == starts.size - 1) minOf(start + tile, size) else (starts[i + 1] + start + tile) / 2
        return from to to
    }

    /**
     * Runs an image-to-image [model] over [src] in windows of [tile] pixels and returns a new
     * bitmap [outScale] times larger (the model itself upscales by [scale]). Windows past the image edge are padded by edge replication.
     * Throws [MlException] with TIMEOUT once [deadlineMs] (elapsedRealtime) passes.
     */
    fun runBitmap(
        src: Bitmap,
        tile: Int,
        margin: Int,
        scale: Int,
        deadlineMs: Long,
        outScale: Int = scale,
        model: (Tensor) -> Tensor,
    ): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w * outScale, h * outScale, Bitmap.Config.ARGB_8888)
        val window = IntArray(tile * tile)
        try {
            for (t in plan(w, h, tile, margin)) {
                if (SystemClock.elapsedRealtime() > deadlineMs) {
                    throw MlException(MlException.Reason.TIMEOUT, "Tiled run over budget")
                }
                val ww = minOf(tile, w - t.x)
                val wh = minOf(tile, h - t.y)
                src.getPixels(window, 0, tile, t.x, t.y, ww, wh)
                padEdges(window, tile, ww, wh)
                val result = model(ImageTensors.argbToNchw(window, tile, tile))
                val ts = tile * scale
                var pixels = ImageTensors.nchwToArgb(result, ts, ts)
                val os = tile * outScale
                if (outScale != scale) {
                    val big = Bitmap.createBitmap(pixels, ts, ts, Bitmap.Config.ARGB_8888)
                    val small = Bitmap.createScaledBitmap(big, os, os, true)
                    pixels = ImageTensors.pixels(small)
                    big.recycle()
                    small.recycle()
                }
                val ox = (t.coreX - t.x) * outScale
                val oy = (t.coreY - t.y) * outScale
                out.setPixels(
                    pixels, oy * os + ox, os,
                    t.coreX * outScale, t.coreY * outScale, t.coreW * outScale, t.coreH * outScale,
                )
            }
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        return out
    }

    private fun padEdges(px: IntArray, stride: Int, w: Int, h: Int) {
        if (w < stride) for (y in 0 until h) {
            val edge = px[y * stride + w - 1]
            for (x in w until stride) px[y * stride + x] = edge
        }
        for (y in h until stride) System.arraycopy(px, (h - 1) * stride, px, y * stride, stride)
    }
}
