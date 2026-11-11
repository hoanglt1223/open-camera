/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import android.graphics.Bitmap
import android.graphics.RectF
import dev.opencamera.app.develop.DevelopActivity.Mode
import dev.opencamera.app.develop.model.BrushShape
import dev.opencamera.app.develop.model.ColorRangeShape
import dev.opencamera.app.develop.model.LinearShape
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.model.MaskComponent
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.model.RadialShape
import dev.opencamera.app.develop.model.Stroke
import dev.opencamera.app.develop.render.ColorMath
import dev.opencamera.app.develop.render.DevelopKernel
import dev.opencamera.app.develop.render.Geometry
import dev.opencamera.app.develop.ui.dp
import kotlin.math.hypot
import kotlin.math.max

/** Single-finger tool gestures on the preview: crop handles, brush, gradients and pickers. */
class DevelopGestures(private val a: DevelopActivity) {
    var active = false
        private set
    var draggingSplit = false
    private var handle = -1
    private var startX = 0f
    private var startY = 0f
    private var startCrop = RectF()
    private val strokePts = ArrayList<Float>()

    // Brush settings, edited from the mask panel.
    var brushSize = 0.05f
    var brushFeather = 0.5f
    var brushFlow = 1f
    var erase = false

    /** Band picked with the HSL targeted tool, for the panel to highlight. */
    var onHslPick: (Int) -> Unit = {}
    var onColorPick: (Float, Float) -> Unit = { _, _ -> }

    fun down(x: Float, y: Float) {
        startX = x; startY = y
        active = when (a.mode) {
            Mode.CROP -> {
                handle = a.overlay.cropHandle(x, y)
                startCrop = cropRect()
                handle >= 0
            }
            Mode.BRUSH -> {
                strokePts.clear()
                addPoint(x, y)
                true
            }
            Mode.LINEAR, Mode.RADIAL -> true
            else -> false
        }
    }

    fun move(x: Float, y: Float) {
        if (!active) return
        when (a.mode) {
            Mode.CROP -> dragCrop(x, y)
            Mode.BRUSH -> {
                addPoint(x, y)
                a.overlay.cursor = floatArrayOf(x, y, brushSize * max(a.overlay.image.width(), a.overlay.image.height()))
                commitStroke(final = false)
            }
            Mode.LINEAR -> {
                a.overlay.line = floatArrayOf(startX, startY, x, y)
                a.overlay.invalidate()
            }
            Mode.RADIAL -> {
                val r = hypot(x - startX, y - startY)
                a.overlay.ellipse = floatArrayOf(startX, startY, r, r)
                a.overlay.invalidate()
            }
            else -> Unit
        }
    }

    fun up(x: Float, y: Float, tap: Boolean) {
        val wasActive = active
        active = false
        when (a.mode) {
            Mode.CROP -> if (wasActive) a.seal()
            Mode.BRUSH -> if (wasActive) { addPoint(x, y); commitStroke(final = true); a.overlay.cursor = null }
            Mode.LINEAR -> if (wasActive && hypot(x - startX, y - startY) > a.dp(16f)) {
                val s0 = a.viewToSource(startX, startY); val s1 = a.viewToSource(x, y)
                setShape(LinearShape(s0[0], s0[1], s1[0], s1[1]))
            }
            Mode.RADIAL -> if (wasActive && hypot(x - startX, y - startY) > a.dp(16f)) {
                val c = a.viewToSource(startX, startY)
                val long = max(a.overlay.image.width(), a.overlay.image.height())
                val r = hypot(x - startX, y - startY) / long
                setShape(RadialShape(c[0], c[1], r, r))
            }
            Mode.PICK_HSL -> if (tap) a.sampleAt(x, y)?.let { p ->
                val hue = ColorMath.hue(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f)
                var best = 0; var bw = -1f
                DevelopKernel.bandWeights(hue) { band, w -> if (w > bw) { bw = w; best = band } }
                onHslPick(best)
            }
            Mode.PICK_COLOR -> if (tap) a.sampleAt(x, y)?.let { p ->
                val r = ((p shr 16) and 255) / 255f; val g = ((p shr 8) and 255) / 255f; val b = (p and 255) / 255f
                onColorPick(ColorMath.hue(r, g, b), maxOf(r, g, b) - minOf(r, g, b))
            }
            else -> Unit
        }
        a.overlay.line = null
        a.overlay.ellipse = null
        updateOverlay()
    }

    fun cancel() {
        active = false
        a.overlay.cursor = null
    }

    private fun addPoint(x: Float, y: Float) {
        val s = a.viewToSource(x, y)
        strokePts += s[0]; strokePts += s[1]
    }

    /** Replaces the last stroke while painting; the gesture is one history step. */
    private fun commitStroke(final: Boolean) {
        val m = a.stack.masks.getOrNull(a.selectedMask) ?: return
        val idx = m.components.indexOfFirst { it.shape is BrushShape }
        val stroke = Stroke(strokePts.toFloatArray(), brushSize, brushFeather, brushFlow, erase)
        val comps = m.components.toMutableList()
        if (idx < 0) {
            comps += MaskComponent(dev.opencamera.app.develop.model.MaskOp.ADD, BrushShape(listOf(stroke)))
        } else {
            val brush = comps[idx].shape as BrushShape
            val strokes = if (strokeOpen) brush.strokes.dropLast(1) + stroke else brush.strokes + stroke
            comps[idx] = comps[idx].copy(shape = BrushShape(strokes))
        }
        strokeOpen = !final
        a.change(a.stack.copy(masks = a.stack.masks.toMutableList().also { it[a.selectedMask] = m.copy(components = comps) }), "Brush", "brush-${m.id}-$strokeId")
        if (final) { strokeId++; a.seal() }
    }

    private var strokeOpen = false
    private var strokeId = 0

    private fun setShape(shape: dev.opencamera.app.develop.model.MaskShape) {
        val m = a.stack.masks.getOrNull(a.selectedMask) ?: return
        val comps = m.components.toMutableList()
        val idx = comps.indexOfFirst { it.shape::class == shape::class }
        if (idx >= 0) comps[idx] = comps[idx].copy(shape = shape) else comps += MaskComponent(dev.opencamera.app.develop.model.MaskOp.ADD, shape)
        replaceMask(m.copy(components = comps), "Gradient")
        a.seal()
    }

    fun replaceMask(m: LocalMask, label: String, coalesce: String? = null) {
        val list = a.stack.masks.toMutableList()
        val i = list.indexOfFirst { it.id == m.id }
        if (i < 0) return
        list[i] = m
        a.change(a.stack.copy(masks = list), label, coalesce)
    }

    private fun cropRect() = RectF(a.stack[Params.CROP_LEFT], a.stack[Params.CROP_TOP], a.stack[Params.CROP_RIGHT], a.stack[Params.CROP_BOTTOM])

    private fun dragCrop(x: Float, y: Float) {
        val r = a.overlay.image
        val dx = (x - startX) / r.width()
        val dy = (y - startY) / r.height()
        val c = RectF(startCrop)
        val min = 0.05f
        when (handle) {
            0 -> { c.left = (c.left + dx).coerceIn(0f, c.right - min); c.top = (c.top + dy).coerceIn(0f, c.bottom - min) }
            1 -> { c.right = (c.right + dx).coerceIn(c.left + min, 1f); c.top = (c.top + dy).coerceIn(0f, c.bottom - min) }
            2 -> { c.left = (c.left + dx).coerceIn(0f, c.right - min); c.bottom = (c.bottom + dy).coerceIn(c.top + min, 1f) }
            3 -> { c.right = (c.right + dx).coerceIn(c.left + min, 1f); c.bottom = (c.bottom + dy).coerceIn(c.top + min, 1f) }
            4 -> {
                val ox = dx.coerceIn(-c.left, 1f - c.right); val oy = dy.coerceIn(-c.top, 1f - c.bottom)
                c.offset(ox, oy)
            }
        }
        // Keep a fixed aspect when one is set: adjust height from width.
        val aspect = a.stack[Params.CROP_ASPECT]
        val pw = a.engine.proxyWidth.toFloat(); val ph = a.engine.proxyHeight.toFloat()
        if (aspect != 0f && handle in 0..3 && pw > 0 && ph > 0) {
            val (tw, th) = Geometry.turnedSize(pw.toInt(), ph.toInt(), a.stack)
            val ratio = if (aspect < 0) pw / ph else aspect
            val h = (c.width() * tw / ratio) / th
            if (handle < 2) c.top = (c.bottom - h).coerceAtLeast(0f) else c.bottom = (c.top + h).coerceAtMost(1f)
        }
        var s = a.stack.with(Params.CROP_LEFT, c.left).with(Params.CROP_TOP, c.top).with(Params.CROP_RIGHT, c.right).with(Params.CROP_BOTTOM, c.bottom)
        if (pw > 0) s = Geometry.fitCrop(s, pw.toInt(), ph.toInt())
        a.change(s, "Crop", "crop")
    }

    fun updateOverlay() {
        val o = a.overlay
        o.crop = if (a.mode == Mode.CROP) cropRect() else null
        o.maskTint = maskTint()
        o.invalidate()
    }

    var showMask = false

    /** Red overlay of the selected mask's coverage in output space. */
    private fun maskTint(): Bitmap? {
        if (!showMask || a.selectedMask < 0) return null
        val idx = a.stack.masks.filter { it.enabled }.indexOfFirst { it.id == a.stack.masks.getOrNull(a.selectedMask)?.id }
        val (cov, w, h) = a.engine.maskCoverage(idx) ?: return null
        val stats = a.lastStats ?: return null
        val ow = minOf(512, stats.outWidth); val oh = max(1, stats.outHeight * ow / max(1, stats.outWidth))
        val f = Geometry.frame(a.displayStack(), max(1, a.engine.proxyWidth), max(1, a.engine.proxyHeight))
        val q = FloatArray(2)
        val px = IntArray(ow * oh) { i ->
            f.outputToSource.map((i % ow + 0.5f) / ow, (i / ow + 0.5f) / oh, q)
            val x = (q[0] * w).toInt(); val y = (q[1] * h).toInt()
            val v = if (x in 0 until w && y in 0 until h) cov[y * w + x] else 0f
            ((v * 140).toInt() shl 24) or 0xff3030
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    fun pickColorFor(mask: LocalMask, hue: Float, sat: Float) {
        val comps = mask.components.toMutableList()
        val i = comps.indexOfFirst { it.shape is ColorRangeShape }
        val shape = ColorRangeShape(hue, sat, (comps.getOrNull(i)?.shape as? ColorRangeShape)?.tolerance ?: 0.3f)
        if (i >= 0) comps[i] = comps[i].copy(shape = shape) else comps += MaskComponent(dev.opencamera.app.develop.model.MaskOp.ADD, shape)
        replaceMask(mask.copy(components = comps), "Colour range")
        a.seal()
    }
}
