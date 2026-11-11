/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.export

import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.render.ColorMath
import org.w3c.dom.Element
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Partial, approximate Lightroom/Camera Raw compatibility (`crs:` namespace, process version
 * 2012/11). Values are copied slider for slider; the underlying algorithms differ, so the same
 * numbers will not look identical. What does not transfer is listed in [NOT_TRANSFERRED].
 */
object CrsXmp {
    const val NS = "http://ns.adobe.com/camera-raw-settings/1.0/"

    /** Shown in the export/import UI and the docs. */
    val NOT_TRANSFERRED = listOf(
        "local masks (brush, gradients, range and AI masks)",
        "looks, LUTs and look intensity",
        "retouch tools",
        "quarter turns, flips and crops of rotated photos (Lightroom stores these with the orientation)",
        "camera profiles and lens corrections",
    )

    /** crs name -> our key, for plain slider-for-slider values. */
    private val DIRECT = linkedMapOf(
        "Exposure2012" to Params.EXPOSURE,
        "Contrast2012" to Params.CONTRAST,
        "Highlights2012" to Params.HIGHLIGHTS,
        "Shadows2012" to Params.SHADOWS,
        "Whites2012" to Params.WHITES,
        "Blacks2012" to Params.BLACKS,
        "IncrementalTemperature" to Params.TEMP,
        "IncrementalTint" to Params.TINT,
        "Vibrance" to Params.VIBRANCE,
        "Saturation" to Params.SATURATION,
        "Clarity2012" to Params.CLARITY,
        "Texture" to Params.TEXTURE,
        "Dehaze" to Params.DEHAZE,
        "ParametricShadows" to Params.PC_SHADOWS,
        "ParametricDarks" to Params.PC_DARKS,
        "ParametricLights" to Params.PC_LIGHTS,
        "ParametricHighlights" to Params.PC_HIGHLIGHTS,
        "Sharpness" to Params.SHARPEN,
        "SharpenRadius" to Params.SHARPEN_RADIUS,
        "SharpenDetail" to Params.SHARPEN_DETAIL,
        "SharpenEdgeMasking" to Params.SHARPEN_MASKING,
        "LuminanceSmoothing" to Params.NR_LUMA,
        "ColorNoiseReduction" to Params.NR_COLOR,
        "PostCropVignetteAmount" to Params.VIGNETTE,
        "PostCropVignetteMidpoint" to Params.VIGNETTE_MIDPOINT,
        "PostCropVignetteRoundness" to Params.VIGNETTE_ROUNDNESS,
        "PostCropVignetteFeather" to Params.VIGNETTE_FEATHER,
        "PostCropVignetteHighlightContrast" to Params.VIGNETTE_HIGHLIGHTS,
        "GrainAmount" to Params.GRAIN,
        "GrainSize" to Params.GRAIN_SIZE,
        "GrainFrequency" to Params.GRAIN_ROUGHNESS,
        "PerspectiveVertical" to Params.PERSPECTIVE_V,
        "PerspectiveHorizontal" to Params.PERSPECTIVE_H,
        "CropAngle" to Params.CROP_ANGLE,
        "SplitToningShadowHue" to Params.grade("shadows", "hue"),
        "SplitToningShadowSaturation" to Params.grade("shadows", "sat"),
        "SplitToningHighlightHue" to Params.grade("highlights", "hue"),
        "SplitToningHighlightSaturation" to Params.grade("highlights", "sat"),
        "SplitToningBalance" to Params.GRADE_BALANCE,
        "ColorGradeMidtoneHue" to Params.grade("midtones", "hue"),
        "ColorGradeMidtoneSat" to Params.grade("midtones", "sat"),
        "ColorGradeMidtoneLum" to Params.grade("midtones", "lum"),
        "ColorGradeShadowLum" to Params.grade("shadows", "lum"),
        "ColorGradeHighlightLum" to Params.grade("highlights", "lum"),
        "ColorGradeGlobalHue" to Params.grade("global", "hue"),
        "ColorGradeGlobalSat" to Params.grade("global", "sat"),
        "ColorGradeGlobalLum" to Params.grade("global", "lum"),
        "ColorGradeBlending" to Params.GRADE_BLENDING,
    ).apply {
        val names = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
        names.forEachIndexed { i, n ->
            val band = Params.HSL_BANDS[i]
            put("HueAdjustment$n", Params.hsl(band, "hue"))
            put("SaturationAdjustment$n", Params.hsl(band, "sat"))
            put("LuminanceAdjustment$n", Params.hsl(band, "lum"))
        }
    }

    private val CURVES = mapOf(
        "ToneCurvePV2012" to "rgb", "ToneCurvePV2012Red" to "r", "ToneCurvePV2012Green" to "g", "ToneCurvePV2012Blue" to "b",
    )

    fun write(stack: EditStack): String = buildString {
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Open Camera\">\n")
        append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("  <rdf:Description rdf:about=\"\" xmlns:crs=\"").append(NS).append("\"\n")
        append("   crs:Version=\"15.0\" crs:ProcessVersion=\"11.0\" crs:WhiteBalance=\"Custom\"")
        for ((crs, key) in DIRECT) {
            val v = stack[key]
            if (v == Params.default(key) && key != Params.EXPOSURE) continue
            append("\n   crs:").append(crs).append("=\"").append(format(crs, key, v)).append("\"")
        }
        val hasCrop = stack[Params.CROP_LEFT] > 0f || stack[Params.CROP_TOP] > 0f ||
            stack[Params.CROP_RIGHT] < 1f || stack[Params.CROP_BOTTOM] < 1f || stack[Params.CROP_ANGLE] != 0f
        val plainFrame = stack[Params.CROP_ROT90] == 0f && stack[Params.CROP_FLIP_H] == 0f && stack[Params.CROP_FLIP_V] == 0f
        if (hasCrop && plainFrame) {
            append("\n   crs:HasCrop=\"True\"")
            append("\n   crs:CropLeft=\"").append(f6(stack[Params.CROP_LEFT])).append("\"")
            append(" crs:CropTop=\"").append(f6(stack[Params.CROP_TOP])).append("\"")
            append(" crs:CropRight=\"").append(f6(stack[Params.CROP_RIGHT])).append("\"")
            append(" crs:CropBottom=\"").append(f6(stack[Params.CROP_BOTTOM])).append("\"")
        }
        append(">\n")
        for ((crs, ch) in CURVES) {
            val pts = stack.curves[ch] ?: continue
            append("   <crs:").append(crs).append("><rdf:Seq>")
            for (p in pts) append("<rdf:li>").append((p.x * 255).roundToInt()).append(", ").append((p.y * 255).roundToInt()).append("</rdf:li>")
            append("</rdf:Seq></crs:").append(crs).append(">\n")
        }
        append("  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n")
    }

    /**
     * Reads a Lightroom/Camera Raw `.xmp` sidecar (attribute or element form) onto [base].
     * Unknown settings are ignored. Absolute `crs:Temperature` (raw files) is converted to a
     * relative warmth around 6500 K.
     */
    fun read(xml: String, base: EditStack = EditStack()): EditStack {
        var stack = base
        val descriptions = XmpWriter.descriptions(xml)
        fun value(d: Element, name: String): String? {
            d.getAttributeNS(NS, name).takeIf { it.isNotBlank() }?.let { return it }
            val els = d.getElementsByTagNameNS(NS, name)
            return if (els.length > 0) els.item(0).textContent?.trim()?.ifBlank { null } else null
        }
        for (d in descriptions) {
            for ((crs, key) in DIRECT) {
                val v = value(d, crs)?.removePrefix("+")?.toFloatOrNull() ?: continue
                stack = stack.with(key, v)
            }
            if (value(d, "IncrementalTemperature") == null) {
                value(d, "Temperature")?.toFloatOrNull()?.takeIf { it > 0f }?.let { k ->
                    val rel = (ln(k / ColorMath.REFERENCE_K) / ln(2.0) / 1.2 * 100).toFloat()
                    stack = stack.with(Params.TEMP, rel)
                }
                value(d, "Tint")?.toFloatOrNull()?.let { stack = stack.with(Params.TINT, it * 0.67f) }
            }
            if (value(d, "HasCrop").equals("True", ignoreCase = true)) {
                listOf("CropLeft" to Params.CROP_LEFT, "CropTop" to Params.CROP_TOP, "CropRight" to Params.CROP_RIGHT,
                    "CropBottom" to Params.CROP_BOTTOM).forEach { (n, k) ->
                    value(d, n)?.toFloatOrNull()?.let { stack = stack.with(k, it) }
                }
            }
            for ((crs, ch) in CURVES) {
                val els = d.getElementsByTagNameNS(NS, crs)
                if (els.length == 0) continue
                val items = (els.item(0) as Element).getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li")
                val pts = (0 until items.length).mapNotNull { i ->
                    val parts = items.item(i).textContent.split(',').map { it.trim().toFloatOrNull() }
                    if (parts.size == 2 && parts[0] != null && parts[1] != null) CurvePoint(parts[0]!! / 255f, parts[1]!! / 255f) else null
                }
                if (pts.size >= 2) stack = stack.withCurve(ch, pts)
            }
        }
        return stack
    }

    private fun format(crs: String, key: String, v: Float): String = when (crs) {
        "Exposure2012" -> String.format(Locale.US, "%+.2f", v)
        "SharpenRadius" -> String.format(Locale.US, "%+.1f", v)
        "CropAngle" -> String.format(Locale.US, "%.2f", v)
        else -> if ((Params[key]?.min ?: 0f) < 0f && v > 0f) "+${v.roundToInt()}" else v.roundToInt().toString()
    }

    private fun f6(v: Float) = String.format(Locale.US, "%.6f", v)
}
