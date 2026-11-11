/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.render

import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.Params
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.ToneTable
import kotlin.math.max
import kotlin.math.pow

/**
 * Slider values turned into renderer units. Built once per stack change and consumed by both
 * the GLSL program and [DevelopKernel], so the GPU and CPU paths share every mapping.
 */
class DevelopUniforms(
    val wb: Mat3,
    val exposure: Float,
    val contrast: Float,
    val highlights: Float,
    val shadows: Float,
    val whites: Float,
    val blacks: Float,
    val vibrance: Float,
    val saturation: Float,
    /** 8 bands x (hue shift in degrees, saturation delta, luminance delta). */
    val hsl: FloatArray,
    /** 4 ranges (shadows, midtones, highlights, global) x (tint rgb, lum). */
    val grading: FloatArray,
    val gradePivot: Float,
    val gradeWidth: Float,
    /** 256 x RGBA, composite tone curve per channel. */
    val curve: FloatArray,
    val clarity: Float,
    val texture: Float,
    val dehaze: Float,
    val atmosphere: FloatArray,
    val sharpen: Float,
    val sharpenRadius: Float,
    val sharpenDetail: Float,
    val sharpenMasking: Float,
    val nrLuma: Float,
    val nrColor: Float,
    val vignette: Float,
    val vignetteMidpoint: Float,
    val vignetteRoundness: Float,
    val vignetteFeather: Float,
    val vignetteHighlights: Float,
    val grain: Float,
    val grainSize: Float,
    val grainRoughness: Float,
    val look: LookParams?,
    /** Per mask slot (4) the deltas of [Params.LOCAL], normalized like the global values. */
    val local: Array<FloatArray>,
    val maskCount: Int,
) {
    /** Compress highlights only when an adjustment can push values past white. */
    val shoulder: Boolean = exposure > 0f || local.any { it[LOCAL_INDEX.getValue(Params.EXPOSURE)] > 0f }

    val curveIsIdentity: Boolean = curve.indices.all { i -> kotlin.math.abs(curve[i] - (i / 4) / 255f) < 1e-6f || i % 4 == 3 }

    companion object {
        const val MASK_SLOTS = 4

        /** Index of each [Params.LOCAL] key in a [local] row. */
        val LOCAL_INDEX = Params.LOCAL.withIndex().associate { it.value to it.index }

        fun from(
            stack: EditStack,
            look: LookParams?,
            atmosphere: FloatArray = floatArrayOf(1f, 1f, 1f),
            wide: Boolean = false,
        ): DevelopUniforms {
            fun n(key: String) = stack.effective(key) / 100f
            val hsl = FloatArray(24)
            Params.HSL_BANDS.forEachIndexed { i, b ->
                hsl[i * 3] = n(Params.hsl(b, "hue")) * 30f
                hsl[i * 3 + 1] = n(Params.hsl(b, "sat"))
                hsl[i * 3 + 2] = n(Params.hsl(b, "lum"))
            }
            val grading = FloatArray(16)
            val rgb = FloatArray(3)
            Params.GRADE_RANGES.forEachIndexed { i, r ->
                val sat = n(Params.grade(r, "sat"))
                ColorMath.hueRgb(stack.effective(Params.grade(r, "hue")), rgb)
                val y = ColorMath.luma(rgb[0], rgb[1], rgb[2])
                for (c in 0..2) grading[i * 4 + c] = (rgb[c] - y) * sat * 0.3f
                grading[i * 4 + 3] = n(Params.grade(r, "lum")) * 0.4f
            }
            val local = Array(MASK_SLOTS) { FloatArray(Params.LOCAL.size) }
            val masks = stack.masks.filter { it.enabled }.take(MASK_SLOTS)
            masks.forEachIndexed { slot, m ->
                for ((key, idx) in LOCAL_INDEX) {
                    val v = (m.values[key] ?: 0f) * m.opacity
                    local[slot][idx] = if (key == Params.EXPOSURE) v else v / 100f
                }
            }
            val lookOn = Group.LOOK !in stack.disabled && stack.lookId != null && look != null
            return DevelopUniforms(
                wb = ColorMath.whiteBalance(n(Params.TEMP), n(Params.TINT), wide),
                exposure = stack.effective(Params.EXPOSURE),
                contrast = n(Params.CONTRAST),
                highlights = n(Params.HIGHLIGHTS),
                shadows = n(Params.SHADOWS),
                whites = n(Params.WHITES),
                blacks = n(Params.BLACKS),
                vibrance = n(Params.VIBRANCE),
                saturation = n(Params.SATURATION),
                hsl = hsl,
                grading = grading,
                gradePivot = 0.5f - 0.25f * n(Params.GRADE_BALANCE),
                gradeWidth = 0.05f + 0.45f * n(Params.GRADE_BLENDING),
                curve = curveTable(stack),
                clarity = n(Params.CLARITY),
                texture = n(Params.TEXTURE),
                dehaze = n(Params.DEHAZE),
                atmosphere = atmosphere,
                sharpen = n(Params.SHARPEN),
                sharpenRadius = stack.effective(Params.SHARPEN_RADIUS),
                sharpenDetail = n(Params.SHARPEN_DETAIL),
                sharpenMasking = n(Params.SHARPEN_MASKING),
                nrLuma = n(Params.NR_LUMA),
                nrColor = n(Params.NR_COLOR),
                vignette = n(Params.VIGNETTE),
                vignetteMidpoint = n(Params.VIGNETTE_MIDPOINT),
                vignetteRoundness = n(Params.VIGNETTE_ROUNDNESS),
                vignetteFeather = n(Params.VIGNETTE_FEATHER),
                vignetteHighlights = n(Params.VIGNETTE_HIGHLIGHTS),
                grain = n(Params.GRAIN),
                grainSize = n(Params.GRAIN_SIZE),
                grainRoughness = n(Params.GRAIN_ROUGHNESS),
                look = if (lookOn) look?.let {
                    LookParams(it.lut, it.tone, it.intensity * n(Params.LOOK_INTENSITY), 0f, 1f, 0f, 0.5f)
                } else null,
                local = local,
                maskCount = masks.size,
            )
        }

        /**
         * Parametric curve (four regions split at 1/4, 1/2, 3/4), then the RGB point curve, then
         * each channel's point curve. Point curves are monotone (Fritsch-Carlson), so tones never
         * invert.
         */
        fun curveTable(stack: EditStack): FloatArray {
            val out = FloatArray(256 * 4)
            val curveOff = Group.CURVE in stack.disabled
            val rgbT = if (curveOff) null else ToneTable.build(stack.curve("rgb").map { it.x to it.y }, 0f)
            val ch = if (curveOff) arrayOfNulls<FloatArray>(3) else arrayOf("r", "g", "b").map {
                if (stack.curves[it] == null) null else ToneTable.build(stack.curve(it).map { p -> p.x to p.y }, 0f)
            }.toTypedArray()
            val pc = floatArrayOf(
                stack.effective(Params.PC_SHADOWS), stack.effective(Params.PC_DARKS),
                stack.effective(Params.PC_LIGHTS), stack.effective(Params.PC_HIGHLIGHTS),
            ).map { it / 100f }
            for (i in 0 until 256) {
                val x = i / 255f
                var y = x
                // Each region bumps its quarter with a smooth bell; the sum stays monotone for |a| <= 1.
                for (k in 0..3) {
                    val c = (k + 0.5f) / 4f
                    val d = (x - c) / 0.25f
                    val bell = max(0f, 1f - d * d).pow(2)
                    y += pc[k] * 0.08f * bell
                }
                y = y.coerceIn(0f, 1f)
                if (rgbT != null) y = sample(rgbT, y)
                for (c in 0..2) out[i * 4 + c] = ch[c]?.let { sample(it, y) } ?: y
                out[i * 4 + 3] = 1f
            }
            return out
        }

        private fun sample(t: FloatArray, v: Float): Float {
            val pos = v.coerceIn(0f, 1f) * 255f
            val i = minOf(pos.toInt(), 254)
            return t[i] + (t[i + 1] - t[i]) * (pos - i)
        }
    }
}
