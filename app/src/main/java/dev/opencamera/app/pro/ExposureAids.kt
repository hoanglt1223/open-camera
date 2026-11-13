/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import kotlin.math.roundToInt

/**
 * Exposure aids drawn over the viewfinder: zebra, focus peaking and false color run as one
 * GPU shader over a small preview copy; the histogram is binned on the CPU from the same copy.
 * The pure math here is mirrored by [SHADER] so the unit tests describe what the GPU draws.
 */
object ExposureAids {
    /** Zebra levels the chip cycles through, in percent of full scale. */
    val ZEBRA_LEVELS = intArrayOf(70, 80, 90, 95, 100)
    const val DEFAULT_ZEBRA_PERCENT = 95

    /** Same scale as [FrameAssists.peakingMask]: |gx| + |gy| of a 0..255 Sobel. */
    const val PEAKING_THRESHOLD = 220

    /** Luma (0..255) at or above which zebra stripes show. 100% means clipped (255) only. */
    fun zebraThresholdLuma(percent: Int): Int = (percent.coerceIn(1, 100) * 255 / 100f).roundToInt()

    fun nextZebraLevel(percent: Int): Int =
        ZEBRA_LEVELS.firstOrNull { it > percent } ?: ZEBRA_LEVELS.first()

    /** A false-color band: luma in [lowPercent, highPercent) is painted [color] (opaque RGB). */
    data class Band(val lowPercent: Float, val highPercent: Float, val color: Int)

    /**
     * Bands read like a cinema false-color monitor: crushed blacks purple, shadows blue,
     * middle grey green, skin pink, near-clip yellow, clipped red. Luma outside every band is
     * shown as greyscale so the bands stand out.
     */
    val FALSE_COLOR_BANDS = listOf(
        Band(0f, 2.5f, 0x7F00FF),
        Band(2.5f, 10f, 0x0050FF),
        Band(40f, 48f, 0x00C000),
        Band(60f, 70f, 0xFF80C0),
        Band(93f, 99f, 0xFFE000),
        Band(99f, 100.01f, 0xFF0000),
    )

    /** Index into [FALSE_COLOR_BANDS] for a 0..255 luma, or -1 for greyscale. */
    fun falseColorBand(luma: Int): Int {
        val percent = luma.coerceIn(0, 255) * 100f / 255f
        return FALSE_COLOR_BANDS.indexOfFirst { percent >= it.lowPercent && percent < it.highPercent }
    }

    /**
     * Luma histogram of ARGB [pixels] (row length [width]) sampling every [step]th pixel in
     * both directions, so a preview copy can be binned in well under a frame.
     */
    fun histogram(pixels: IntArray, width: Int, step: Int = 2, bins: Int = 64): IntArray {
        require(width > 0 && step > 0 && bins in 1..256) { "width=$width step=$step bins=$bins" }
        val out = IntArray(bins)
        val height = pixels.size / width
        var y = 0
        while (y < height) {
            var i = y * width
            val end = i + width
            while (i < end) {
                out[FrameAssists.luma(pixels[i]) * bins shr 8]++
                i += step
            }
            y += step
        }
        return out
    }

    /** Why the GPU aids turned themselves off. Preview and capture are never affected. */
    enum class Unavailable { API_LEVEL, SHADER_COMPILE, FRAME_COPY, DRAW }

    /** First reason the GPU path cannot run on [sdkInt], before any shader work. */
    fun gpuSupport(sdkInt: Int): Unavailable? = if (sdkInt < MIN_GPU_SDK) Unavailable.API_LEVEL else null

    const val MIN_GPU_SDK = 33

    /** What actually runs for [settings] given the current [unavailable] reason. */
    data class Active(val zebra: Boolean, val peaking: Boolean, val falseColor: Boolean, val histogram: Boolean) {
        val shader get() = zebra || peaking || falseColor
        val any get() = shader || histogram
    }

    fun active(settings: ProSettings, unavailable: Unavailable?): Active {
        // Without frames nothing can run; any other failure only stops the shader aids.
        val shaderOk = unavailable == null
        val framesOk = unavailable != Unavailable.FRAME_COPY
        return Active(
            zebra = settings.zebra && shaderOk,
            peaking = settings.peaking && shaderOk,
            falseColor = settings.falseColor && shaderOk,
            histogram = settings.histogram && framesOk,
        )
    }

    /** True when [settings] asks for an aid that [unavailable] switched off (time for the notice). */
    fun suppressed(settings: ProSettings, unavailable: Unavailable?): Boolean {
        val a = active(settings, unavailable)
        return (settings.zebra && !a.zebra) || (settings.peaking && !a.peaking) ||
            (settings.falseColor && !a.falseColor) || (settings.histogram && !a.histogram)
    }

    /**
     * Counts consecutive failed preview copies; a few misses during a rebind are normal,
     * a long run means copies do not work on this device.
     */
    class CopyFailures(private val limit: Int = 60) {
        private var run = 0

        /** Returns true once [limit] copies in a row have failed. */
        fun record(success: Boolean): Boolean {
            run = if (success) 0 else run + 1
            return run >= limit
        }

        fun reset() {
            run = 0
        }
    }

    private fun rgb(c: Int) = "half3(%.4f, %.4f, %.4f)".format(
        java.util.Locale.ROOT,
        ((c shr 16) and 0xff) / 255f, ((c shr 8) and 0xff) / 255f, (c and 0xff) / 255f,
    )

    private fun f(v: Float) = "%.4f".format(java.util.Locale.ROOT, v)

    /**
     * AGSL: `frame` is the preview copy scaled to the view, `texel` one copy pixel in view
     * pixels. Output is premultiplied and transparent where no aid applies.
     */
    val SHADER: String = buildString {
        appendLine("uniform shader frame;")
        appendLine("uniform float2 texel;")
        appendLine("uniform float zebraOn;")
        appendLine("uniform float zebraLevel;")
        appendLine("uniform float stripe;")
        appendLine("uniform float peakingOn;")
        appendLine("uniform float peakingThreshold;")
        appendLine("uniform float falseColorOn;")
        appendLine("float luma(float2 p) { half3 c = frame.eval(p).rgb; return dot(float3(c), float3(${f(77 / 256f)}, ${f(150 / 256f)}, ${f(29 / 256f)})) * 255.0; }")
        appendLine("half4 main(float2 p) {")
        appendLine("  float y = luma(p);")
        appendLine("  if (zebraOn > 0.5 && y >= zebraLevel - 0.5 && mod(floor((p.x + p.y) / stripe), 2.0) < 1.0) return half4(0.69, 0.69, 0.69, 0.69);")
        appendLine("  if (peakingOn > 0.5) {")
        appendLine("    float2 dx = float2(texel.x, 0.0); float2 dy = float2(0.0, texel.y);")
        appendLine("    float tl = luma(p - dx - dy); float t = luma(p - dy); float tr = luma(p + dx - dy);")
        appendLine("    float l = luma(p - dx); float r = luma(p + dx);")
        appendLine("    float bl = luma(p - dx + dy); float b = luma(p + dy); float br = luma(p + dx + dy);")
        appendLine("    float gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);")
        appendLine("    float gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);")
        appendLine("    if (abs(gx) + abs(gy) > peakingThreshold) return half4(1.0, 0.231, 0.188, 1.0);")
        appendLine("  }")
        appendLine("  if (falseColorOn > 0.5) {")
        appendLine("    float pc = y * ${f(100f / 255f)};")
        for (band in FALSE_COLOR_BANDS) {
            appendLine("    if (pc >= ${f(band.lowPercent)} && pc < ${f(band.highPercent)}) return half4(${rgb(band.color)}, 1.0);")
        }
        appendLine("    half g = half(y / 255.0);")
        appendLine("    return half4(g, g, g, 1.0);")
        appendLine("  }")
        appendLine("  return half4(0.0);")
        appendLine("}")
    }
}
