/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.graphics.Rect
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.tasks.SaliencyDetector
import dev.opencamera.ml.tasks.Segmenter
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tap-to-select masks for object removal: the person under the tap (selfie segmenter), else
 * the salient object under it (U2-Net-p when downloaded), else a colour region grown from the
 * tap. Results are grown a little so edges and contact shadows are removed too.
 */
class SelectionMasker(private val segmenter: Segmenter, private val saliency: SaliencyDetector) {
    enum class Source { PERSON, OBJECT, REGION }

    fun select(image: Bitmap, tap: NPoint): Pair<HoleMask, Source> {
        val small = ImageTensors.scaled(image, WORK, WORK)
        try {
            val sx = (tap.x * WORK).toInt().coerceIn(0, WORK - 1)
            val sy = (tap.y * WORK).toInt().coerceIn(0, WORK - 1)
            var source = Source.PERSON
            var soft = segmenter.segment(small)?.person?.let { p -> resample(p.data, p.width, p.height) }
                ?.let { MaskMath.component(it, WORK, WORK, sx, sy) }
            if (soft == null || soft.none { it > 0f }) {
                source = Source.OBJECT
                val (sal, byModel) = saliency.saliency(small)
                soft = if (byModel) MaskMath.component(resample(sal.data, sal.width, sal.height), WORK, WORK, sx, sy) else null
            }
            if (soft == null || soft.none { it > 0f } || soft.count { it > 0f } > WORK * WORK * 0.6f) {
                source = Source.REGION
                soft = MaskMath.regionGrow(ImageTensors.pixels(small), WORK, WORK, sx, sy, tolerance = 30f, maxFraction = 0.25f)
            }
            val mask = HoleMask.fromSoft(soft, WORK, WORK, image.width, image.height)
            return mask.dilated(max(2, (max(image.width, image.height) * 0.012f).roundToInt())) to source
        } finally {
            if (small !== image) small.recycle()
        }
    }

    private fun resample(d: FloatArray, w: Int, h: Int): FloatArray {
        if (w == WORK && h == WORK) return d
        val m = dev.opencamera.ml.tasks.Mask(w, h, d)
        return FloatArray(WORK * WORK) { i -> m.sample((i % WORK + 0.5f) / WORK, (i / WORK + 0.5f) / WORK) }
    }

    private companion object {
        const val WORK = 256
    }
}

/**
 * Object / person removal. Input: brush strokes and/or taps that select a person or object.
 * Methods: MI-GAN (bundled default), big-lama (optional download), PatchMatch, Telea, with
 * automatic fallback down that chain unless a comparison grid asks for one method strictly.
 */
class EraseTool(private val inpainter: Inpainter, private val selector: SelectionMasker) : RetouchTool {
    override val id = "erase"
    override val input = InputKind.BRUSH
    override val defaultStrength = 1f

    override fun methods(): List<RetouchMethod> {
        val avail = inpainter.available()
        return InpaintMethod.entries.map { m ->
            val reason = when {
                m in avail -> null
                m == InpaintMethod.LAMA -> "not downloaded"
                else -> "unavailable"
            }
            RetouchMethod(m.id, reason == null, reason)
        }
    }

    override fun unavailableReason(): String? = if (inpainter.available().isEmpty()) "no inpainting backend" else null

    override fun tagsFor(params: RetouchParams) =
        if (params.method == InpaintMethod.MIGAN.id || params.method == InpaintMethod.LAMA.id) setOf(RetouchTags.GENERATED) else emptySet()

    /** Mask of [params] at the size of [image]: strokes plus tap selections. */
    fun mask(image: Bitmap, params: RetouchParams): HoleMask {
        var mask = HoleMask.fromStrokes(params.strokes.filter { !it.erase }, image.width, image.height)
        for (tap in params.taps) mask = mask.union(selector.select(image, tap).first)
        val erasers = params.strokes.filter { it.erase }
        if (erasers.isNotEmpty()) {
            val cut = HoleMask.fromStrokes(erasers.map { it.copy(erase = false) }, image.width, image.height)
            for (i in mask.data.indices) if (cut.data[i].toInt() != 0) mask.data[i] = 0
        }
        return mask
    }

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        val mask = mask(image, params)
        if (mask.isEmpty) throw RetouchUnavailable("nothing selected")
        val strict = params.value("strict") > 0f
        val undo = UndoPatch.local(image, inpainter.affectedRect(image, mask))
        val out = inpainter.inpaint(image, mask, InpaintMethod.of(params.method), strict)
        val resolved = params.copy(method = out.method.id, values = params.values - "strict")
        return RetouchResult(image, undo, out.method.id, out.fallbackReason, tagsFor(resolved), resolved)
    }
}

/**
 * Spot / blemish removal by tap. A difference of Gaussians around the tap finds the spot;
 * small spots are filled with Telea, larger ones with PatchMatch from the surrounding skin plus
 * a Poisson (membrane) seam correction, like a healing brush.
 */
class BlemishTool : RetouchTool {
    override val id = "blemish"
    override val input = InputKind.TAP
    override val defaultStrength = 1f

    override fun methods() = listOf(
        RetouchMethod(AUTO, true),
        RetouchMethod(InpaintMethod.TELEA.id, dev.opencamera.pipeline.OpenCv.available),
        RetouchMethod(HEAL, NativeRetouch.available),
    )

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        if (params.taps.isEmpty()) throw RetouchUnavailable("tap a spot")
        val long = max(image.width, image.height)
        val radius = max(4f, params.value("radius", DEFAULT_RADIUS) * long)
        val rects = params.taps.map { t -> spotCrop(image, t, radius) }
        val union = Rect(rects[0]).apply { rects.forEach { union(it) } }
        val undo = UndoPatch.local(image, union)
        val used = LinkedHashSet<String>()
        for ((i, tap) in params.taps.withIndex()) used += heal(image, tap, radius, rects[i], params.method ?: AUTO)
        val method = used.joinToString("+")
        return RetouchResult(image, undo, method, resolved = params.copy(method = params.method ?: AUTO))
    }

    private fun spotCrop(image: Bitmap, t: NPoint, radius: Float): Rect {
        val cx = (t.x * image.width).roundToInt()
        val cy = (t.y * image.height).roundToInt()
        val half = (radius * 4).roundToInt()
        return Rect(cx - half, cy - half, cx + half, cy + half).apply { intersect(0, 0, image.width, image.height) }
    }

    private fun heal(image: Bitmap, tap: NPoint, radius: Float, crop: Rect, method: String): String {
        if (crop.width() < 8 || crop.height() < 8) return "skipped"
        val w = crop.width()
        val h = crop.height()
        val px = IntArray(w * h).also { image.getPixels(it, 0, w, crop.left, crop.top, w, h) }
        val cx = tap.x * image.width - crop.left
        val cy = tap.y * image.height - crop.top
        val spot = BlemishMath.detect(px, w, h, cx, cy, radius)
        val mask = HoleMask(w, h, spot)
        val equivalentRadius = sqrt(mask.area / Math.PI).toFloat()
        val useTelea = when (method) {
            InpaintMethod.TELEA.id -> true
            HEAL -> false
            else -> equivalentRadius < SMALL_SPOT_PX || !NativeRetouch.available
        }
        val work = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        try {
            if (useTelea) {
                Inpainter.telea(work, mask, radius = max(3.0, equivalentRadius / 2.0))
            } else {
                val original = work.copy(Bitmap.Config.ARGB_8888, false)
                val grown = mask.dilated(2)
                val code = NativeRetouch.patchMatch(work, grown.data, 3, 1500.0)
                if (code != NativeRetouch.OK) {
                    original.recycle()
                    Inpainter.telea(work, mask, radius = max(3.0, equivalentRadius / 2.0))
                    return InpaintMethod.TELEA.id
                }
                NativeRetouch.healSeams(work, original, grown.data)
                original.recycle()
            }
            image.setPixels(ImageTensors.pixels(work), 0, w, crop.left, crop.top, w, h)
        } finally {
            work.recycle()
        }
        return if (useTelea) InpaintMethod.TELEA.id else HEAL
    }

    companion object {
        const val AUTO = "auto"
        const val HEAL = "heal"
        /** Default spot radius as a fraction of the long side (about 25 px at 2048). */
        const val DEFAULT_RADIUS = 0.012f
        /** At or above this equivalent radius the healing brush is used instead of Telea. */
        const val SMALL_SPOT_PX = 10f
    }
}

/** Spot detection for [BlemishTool]; pure so it is unit-tested. */
object BlemishMath {
    /**
     * Returns a w x h byte mask of the spot near (cx, cy): pixels within [radius] whose luma is
     * darker than a wide Gaussian-like surround by more than a contrast threshold (difference
     * of box-blurred Gaussians), grown by one pixel. Falls back to a disc of 0.6 radius.
     */
    fun detect(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float): ByteArray {
        val luma = FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * (c shr 16 and 0xff) + 0.587f * (c shr 8 and 0xff) + 0.114f * (c and 0xff)
        }
        val fine = MaskMath.boxBlur(MaskMath.boxBlur(luma, w, h, 1), w, h, 1)
        val rs = max(2, (radius * 1.5f).roundToInt())
        val surround = MaskMath.boxBlur(MaskMath.boxBlur(luma, w, h, rs), w, h, rs)
        val out = ByteArray(w * h)
        var count = 0
        var maxDiff = 0f
        for (y in 0 until h) for (x in 0 until w) {
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) > radius) continue
            maxDiff = max(maxDiff, surround[y * w + x] - fine[y * w + x])
        }
        val threshold = max(6f, maxDiff * 0.35f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (hypot(x + 0.5f - cx, y + 0.5f - cy) > radius) continue
            if (surround[i] - fine[i] >= threshold) {
                out[i] = 1
                count++
            }
        }
        if (count < 4) {
            val r = radius * 0.6f
            for (y in 0 until h) for (x in 0 until w) if (hypot(x + 0.5f - cx, y + 0.5f - cy) <= r) out[y * w + x] = 1
            return out
        }
        val grown = out.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (out[y * w + x].toInt() == 0) continue
            for (dy in -1..1) for (dx in -1..1) {
                val nx = min(w - 1, max(0, x + dx))
                val ny = min(h - 1, max(0, y + dy))
                grown[ny * w + nx] = 1
            }
        }
        return grown
    }
}
