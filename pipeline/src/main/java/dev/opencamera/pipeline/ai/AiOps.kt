/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.ml.tasks.ImageRestorer
import dev.opencamera.ml.tasks.Segmenter
import dev.opencamera.pipeline.retouch.RetouchStack
import dev.opencamera.pipeline.retouch.RetouchToolbox
import org.json.JSONObject

/**
 * ML steps of a processing job, stored as JSON in the job row so they survive process death.
 * Steps run before the look and frame: portrait blur, beauty, denoise, then upscale.
 */
data class AiOps(
    val portraitAperture: Float? = null,
    val focusX: Float? = null,
    val focusY: Float? = null,
    val beauty: Float = 0f,
    val denoise: Float = 0f,
    val upscale: Int = 1,
    /** Editor jobs write a new photo and keep the source. */
    val keepSource: Boolean = false,
    val budgetMs: Long = DEFAULT_BUDGET_MS,
    /** Retouch editor edits ([RetouchStack] JSON), replayed first on the upright full image. */
    val retouch: String? = null,
) {
    val isEmpty get() = portraitAperture == null && beauty <= 0f && denoise <= 0f && upscale <= 1 && retouch == null

    fun toJson(): String = JSONObject().apply {
        portraitAperture?.let { put("aperture", it.toDouble()) }
        focusX?.let { put("fx", it.toDouble()) }
        focusY?.let { put("fy", it.toDouble()) }
        if (beauty > 0f) put("beauty", beauty.toDouble())
        if (denoise > 0f) put("denoise", denoise.toDouble())
        if (upscale > 1) put("upscale", upscale)
        if (keepSource) put("keep", true)
        retouch?.let { put("retouch", it) }
        put("budget", budgetMs)
    }.toString()

    companion object {
        const val DEFAULT_BUDGET_MS = 60_000L

        fun fromJson(json: String?): AiOps? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                fun f(k: String) = if (o.has(k)) o.getDouble(k).toFloat() else null
                AiOps(
                    portraitAperture = f("aperture")?.coerceIn(0f, 1f),
                    focusX = f("fx"),
                    focusY = f("fy"),
                    beauty = (f("beauty") ?: 0f).coerceIn(0f, 1f),
                    denoise = (f("denoise") ?: 0f).coerceIn(0f, 1f),
                    upscale = o.optInt("upscale", 1).takeIf { it == 2 || it == 4 } ?: 1,
                    keepSource = o.optBoolean("keep", false),
                    budgetMs = o.optLong("budget", DEFAULT_BUDGET_MS).coerceIn(1_000L, 600_000L),
                    retouch = if (o.has("retouch")) o.getString("retouch") else null,
                )
            }.getOrNull()
        }
    }
}

/** Runs [AiOps] on a decoded photo. Each step degrades on its own; a failed step is skipped. */
class AiStage(context: Context) {
    data class Report(val steps: List<String>) {
        override fun toString() = steps.joinToString(", ")
    }

    private val runtime = MlRuntime.get(context)
    private val segmenter = Segmenter(runtime)
    private val bokeh = BokehProcessor(segmenter)
    private val beauty = BeautyProcessor(FaceMesh(runtime), segmenter)
    private val enhance = EnhanceProcessor(ImageRestorer(runtime))
    private val retouch by lazy { RetouchToolbox(context) }

    /** Metadata tags required by the replayed retouch edits (see [RetouchExif]). */
    val retouchTags = HashSet<String>()

    /** Returns the bitmap to continue with ([bitmap] itself or a new one; the old is recycled). */
    fun apply(bitmap: Bitmap, rotation: Int, ops: AiOps, report: MutableList<String>): Bitmap {
        var current = bitmap
        RetouchStack.fromJson(ops.retouch)?.let { stack ->
            report += step("retouch") {
                // Edits are recorded on the upright image; rotate there and back.
                val upright = Masks.rotated(current, rotation)
                if (upright !== current) {
                    current.recycle()
                    current = upright
                }
                val r = retouch.replay(current, stack.active)
                current = r.bitmap
                retouchTags += r.tags
                val back = Masks.rotated(current, -rotation)
                if (back !== current) {
                    current.recycle()
                    current = back
                }
                r.report.joinToString("/")
            }
        }
        ops.portraitAperture?.let { aperture ->
            report += step("portrait") {
                bokeh.process(current, rotation, BokehProcessor.Params(aperture, ops.focusX, ops.focusY)).toString()
            }
        }
        if (ops.beauty > 0f) {
            report += step("beauty") { beauty.process(current, rotation, ops.beauty).toString() }
        }
        if (ops.denoise > 0f) {
            report += step("denoise") {
                val r = enhance.denoise(current, ops.denoise, ops.budgetMs)
                if (r.bitmap !== current) {
                    current.recycle()
                    current = r.bitmap
                }
                "${r.method}${r.fallbackReason?.let { " ($it)" } ?: ""}"
            }
        }
        if (ops.upscale > 1) {
            report += step("upscale") {
                val r = enhance.upscale(current, ops.upscale, ops.budgetMs)
                current.recycle()
                current = r.bitmap
                "${r.method}${r.fallbackReason?.let { " ($it)" } ?: ""}"
            }
        }
        return current
    }

    private inline fun step(name: String, block: () -> String): String = try {
        "$name=${block()}"
    } catch (e: Exception) {
        Log.w(TAG, "Step $name skipped", e)
        "$name=skipped(${e.javaClass.simpleName})"
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Step $name out of memory", e)
        "$name=skipped(OOM)"
    }

    private companion object {
        const val TAG = "AiStage"
    }
}
