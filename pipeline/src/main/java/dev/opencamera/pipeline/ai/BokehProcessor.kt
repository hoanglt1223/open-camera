/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import android.graphics.Bitmap
import android.util.Log
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.tasks.Mask
import dev.opencamera.ml.tasks.Segmenter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure portrait maths, unit tested. */
object BokehMath {
    /** f-number shown to the user for an aperture slider position in [0, 1]. */
    fun fNumber(aperture: Float) = 16f - 15f * aperture.coerceIn(0f, 1f)

    /** Disc radius in pixels at a working width; 0 means no blur. */
    fun radius(aperture: Float, workWidth: Int) = aperture.coerceIn(0f, 1f) * workWidth * MAX_RADIUS_FRACTION

    const val MAX_RADIUS_FRACTION = 0.028f

    /**
     * Sharpness map from a subject mask. A focus point on the background (mask < 0.5 there)
     * swaps the roles: the background stays sharp and the subject is blurred.
     */
    fun sharpness(mask: Mask, focusX: Float?, focusY: Float?): FloatArray {
        val onBackground = focusX != null && focusY != null && mask.sample(focusX, focusY) < 0.5f
        return if (onBackground) FloatArray(mask.data.size) { 1f - mask.data[it] } else mask.data.copyOf()
    }

    /** Sharpness from a depth map (any unit): 1 within [depthOfField] of the focus depth. */
    fun sharpnessFromDepth(depth: Mask, focusX: Float, focusY: Float, depthOfField: Float): FloatArray {
        val focus = depth.sample(focusX, focusY)
        val falloff = max(depthOfField, 1e-3f)
        return FloatArray(depth.data.size) { i ->
            (1f - (abs(depth.data[i] - focus) - falloff) / falloff).coerceIn(0f, 1f)
        }
    }
}

/**
 * Portrait lens blur. [prepare] segments and refines the subject once (about 1 MP working copy);
 * [render] can then be called again for any aperture or focus point, which is what the editor
 * does when the user moves the slider or taps.
 */
class BokehProcessor(private val segmenter: Segmenter) {
    data class Params(val aperture: Float, val focusX: Float? = null, val focusY: Float? = null)

    /** [work] is the downscaled image, [mask] the refined subject mask at the same size. */
    class Prepared(val work: Bitmap, val mask: Mask, val model: String, val subjectCoverage: Float) {
        fun release() = work.recycle()
    }

    sealed class Outcome {
        data object Applied : Outcome()
        data object NoSubject : Outcome()
        data class Unavailable(val reason: String) : Outcome()
    }

    /** Null when no model or native code is available, or no person is found. */
    /** [rotation]: clockwise degrees that make [full] upright (EXIF), so people are segmented upright. */
    fun prepare(full: Bitmap, rotation: Int = 0, workLongSide: Int = WORK_LONG_SIDE, depth: Mask? = null, preferMulticlass: Boolean = true): Prepared? {
        if (!NativeAi.available) return null
        val k = workLongSide.toFloat() / max(full.width, full.height)
        val w = (full.width * k).roundToInt().coerceAtLeast(16)
        val h = (full.height * k).roundToInt().coerceAtLeast(16)
        val work = ImageTensors.scaled(full, w, h).let { if (it === full) it.copy(Bitmap.Config.ARGB_8888, true) else it }
        val mask: Mask
        val model: String
        if (depth != null) {
            mask = resample(depth, w, h)
            model = "depth"
        } else {
            val upright = Masks.rotated(work, rotation)
            val seg = segmenter.segment(upright, preferMulticlass)
            if (upright !== work) upright.recycle()
            if (seg == null) {
                work.recycle()
                return null
            }
            // Hair is part of the subject; the multiclass model labels it separately.
            val person = seg.hair?.let { hair ->
                Mask(seg.person.width, seg.person.height, FloatArray(seg.person.data.size) { max(seg.person.data[it], hair.data[it]) })
            } ?: seg.person
            mask = Masks.unrotate(person, rotation, w, h)
            model = seg.model
            val radius = max(2, w / 160)
            if (NativeAi.refineMask(work, mask.data, radius, REFINE_EPS) != 0) {
                Log.w(TAG, "Mask refinement failed, using the raw mask")
            }
        }
        val coverage = mask.coverage()
        if (depth == null && coverage < MIN_COVERAGE) {
            work.recycle()
            return null
        }
        return Prepared(work, mask, model, coverage)
    }

    /** Applies the blur to [full] in place. */
    fun render(full: Bitmap, prepared: Prepared, params: Params): Boolean {
        val sharp = BokehMath.sharpness(prepared.mask, params.focusX, params.focusY)
        val blurred = blurred(prepared, sharp, params) ?: return false
        return try {
            NativeAi.composite(full, blurred, sharp) == 0
        } finally {
            blurred.recycle()
        }
    }

    /** Working-resolution result for previews; caller recycles it. */
    fun renderPreview(prepared: Prepared, params: Params): Bitmap? {
        val sharp = BokehMath.sharpness(prepared.mask, params.focusX, params.focusY)
        val blurred = blurred(prepared, sharp, params) ?: return null
        val out = prepared.work.copy(Bitmap.Config.ARGB_8888, true)
        val ok = NativeAi.composite(out, blurred, sharp) == 0
        blurred.recycle()
        if (!ok) {
            out.recycle()
            return null
        }
        return out
    }

    /**
     * Live overlay: the blurred background with alpha = 1 - sharpness, so the subject stays
     * transparent and the live preview underneath shows through sharp. [mask] must match the
     * work size; caller recycles the result.
     */
    fun renderBackgroundLayer(prepared: Prepared, mask: Mask, params: Params): Bitmap? {
        if (mask.width != prepared.work.width || mask.height != prepared.work.height) return null
        val sharp = BokehMath.sharpness(mask, params.focusX, params.focusY)
        val blurred = blurred(prepared, sharp, params) ?: return null
        val px = IntArray(blurred.width * blurred.height)
        blurred.getPixels(px, 0, blurred.width, 0, 0, blurred.width, blurred.height)
        for (i in px.indices) {
            val a = ((1f - sharp[i]).coerceIn(0f, 1f) * 255f).roundToInt()
            px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
        }
        blurred.setHasAlpha(true)
        blurred.setPixels(px, 0, blurred.width, 0, 0, blurred.width, blurred.height)
        return blurred
    }

    fun process(full: Bitmap, rotation: Int, params: Params): Outcome {
        if (!NativeAi.available) return Outcome.Unavailable("native")
        val prepared = prepare(full, rotation) ?: return Outcome.NoSubject
        return try {
            if (render(full, prepared, params)) Outcome.Applied else Outcome.Unavailable("render")
        } finally {
            prepared.release()
        }
    }

    private fun blurred(prepared: Prepared, sharp: FloatArray, params: Params): Bitmap? {
        val work = prepared.work
        val out = Bitmap.createBitmap(work.width, work.height, Bitmap.Config.ARGB_8888)
        val code = NativeAi.lensBlur(work, sharp, BokehMath.radius(params.aperture, work.width), out)
        if (code != 0) {
            Log.w(TAG, "Lens blur failed: $code")
            out.recycle()
            return null
        }
        return out
    }

    companion object {
        private const val TAG = "BokehProcessor"
        const val WORK_LONG_SIDE = 1024
        private const val REFINE_EPS = 1e-3f
        private const val MIN_COVERAGE = 0.01f

        fun resample(m: Mask, w: Int, h: Int): Mask {
            if (m.width == w && m.height == h) return Mask(w, h, m.data.copyOf())
            val out = Mask(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                out.data[y * w + x] = m.sample((x + 0.5f) / w, (y + 0.5f) / h)
            }
            return out
        }
    }
}
