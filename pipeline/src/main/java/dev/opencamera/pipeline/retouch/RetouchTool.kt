/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/** Point in normalized image coordinates (0..1 on both axes) of the upright image. */
data class NPoint(val x: Float, val y: Float)

/** A brush stroke; [radius] is a fraction of the image's long side. [erase] removes from the mask. */
data class Stroke(val points: List<NPoint>, val radius: Float, val erase: Boolean = false)

/** What the host screen must collect before a tool can run. */
enum class InputKind { NONE, TAP, BRUSH }

/**
 * Everything a tool needs to re-run an edit at any resolution. Coordinates are normalized so
 * the same params render the 2048 px preview and the full-resolution export.
 */
data class RetouchParams(
    val strength: Float = 0f,
    /** One of the tool's [RetouchTool.methods] ids; null = the tool's default chain. */
    val method: String? = null,
    val taps: List<NPoint> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
    /** Tool-specific values (e.g. "slim", "vertical"), validated and capped by the tool. */
    val values: Map<String, Float> = emptyMap(),
) {
    fun value(key: String, default: Float = 0f) = values[key] ?: default

    fun toJson(): JSONObject = JSONObject().apply {
        put("strength", strength.toDouble())
        method?.let { put("method", it) }
        if (taps.isNotEmpty()) put("taps", JSONArray().apply { taps.forEach { put(point(it)) } })
        if (strokes.isNotEmpty()) {
            put("strokes", JSONArray().apply {
                strokes.forEach { s ->
                    put(JSONObject().apply {
                        put("r", s.radius.toDouble())
                        if (s.erase) put("erase", true)
                        put("pts", JSONArray().apply { s.points.forEach { put(point(it)) } })
                    })
                }
            })
        }
        if (values.isNotEmpty()) put("values", JSONObject().apply { values.forEach { (k, v) -> put(k, v.toDouble()) } })
    }

    companion object {
        private fun point(p: NPoint) = JSONArray().put(p.x.toDouble()).put(p.y.toDouble())
        private fun point(a: JSONArray) = NPoint(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())

        fun fromJson(o: JSONObject): RetouchParams {
            val taps = o.optJSONArray("taps")?.let { a -> (0 until a.length()).map { point(a.getJSONArray(it)) } }
            val strokes = o.optJSONArray("strokes")?.let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    val pts = s.getJSONArray("pts")
                    Stroke((0 until pts.length()).map { point(pts.getJSONArray(it)) }, s.getDouble("r").toFloat(), s.optBoolean("erase"))
                }
            }
            val values = o.optJSONObject("values")?.let { v ->
                v.keys().asSequence().associateWith { v.getDouble(it).toFloat() }
            }
            return RetouchParams(
                strength = o.optDouble("strength", 0.0).toFloat(),
                method = if (o.has("method")) o.getString("method") else null,
                taps = taps.orEmpty(),
                strokes = strokes.orEmpty(),
                values = values.orEmpty(),
            )
        }
    }
}

/**
 * Restores the state before an edit. A local edit keeps only the pixels of [rect]; an edit that
 * changes the size (crop, rotate) keeps the whole previous bitmap in [previous].
 */
class UndoPatch private constructor(val rect: Rect?, private val pixels: IntArray?, val previous: Bitmap?) {
    val bytes: Long get() = (pixels?.size?.toLong() ?: 0L) * 4 + (previous?.allocationByteCount?.toLong() ?: 0L)

    /** Returns the bitmap to continue with: [current] patched in place, or [previous]. */
    fun restore(current: Bitmap): Bitmap {
        if (previous != null) return previous
        val r = rect ?: return current
        current.setPixels(pixels!!, 0, r.width(), r.left, r.top, r.width(), r.height())
        return current
    }

    companion object {
        fun local(image: Bitmap, rect: Rect): UndoPatch {
            val r = Rect(rect).apply { if (!intersect(0, 0, image.width, image.height)) setEmpty() }
            if (r.isEmpty) return UndoPatch(null, null, null)
            val px = IntArray(r.width() * r.height())
            image.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
            return UndoPatch(r, px, null)
        }

        fun whole(previous: Bitmap) = UndoPatch(null, null, previous)
        val NONE = UndoPatch(null, null, null)
    }
}

/**
 * Outcome of [RetouchTool.apply]. [bitmap] is the input bitmap edited in place or a new one
 * (then [undo] holds the old). [method] names what actually ran after fallbacks; [tags] mark
 * edits that must be disclosed in metadata (see [RetouchTags]).
 */
class RetouchResult(
    val bitmap: Bitmap,
    val undo: UndoPatch,
    val method: String,
    val note: String? = null,
    val tags: Set<String> = emptySet(),
    /** Params with automatic values resolved (method, angles, gains), stored for exact replay. */
    val resolved: RetouchParams? = null,
)

/** A processing variant shown side by side in the comparison grid. */
data class RetouchMethod(val id: String, val available: Boolean, val reason: String? = null)

/** Thrown when a tool cannot run at all (no face, no mask, library missing). Never a crash. */
class RetouchUnavailable(message: String) : Exception(message)

object RetouchTags {
    /** Face or body shape changed; written to EXIF/XMP by the exporter. */
    const val RESHAPE = "reshape"
    /** Pixels synthesized by a generative model (object removal). */
    const val GENERATED = "generated"
}

/**
 * An editor tool: mask or tap input in, edited bitmap plus undo data out. Implementations are
 * deterministic for the same params, never throw anything but [RetouchUnavailable] for expected
 * failures, and cap their own strength so a host cannot exceed the limits.
 */
interface RetouchTool {
    val id: String
    val input: InputKind
    val defaultStrength: Float
    val maxStrength: Float get() = 1f

    /** Variants for the comparison grid; one entry for tools with a single method. */
    fun methods(): List<RetouchMethod> = listOf(RetouchMethod("default", true))

    /** Metadata tags this edit requires (see [RetouchTags]). */
    fun tagsFor(params: RetouchParams): Set<String> = emptySet()

    /** Null when the tool can run; otherwise the reason shown to the user. */
    fun unavailableReason(): String? = null

    /** Edits [image] (upright). May modify it in place within the area recorded in the undo patch. */
    fun apply(image: Bitmap, params: RetouchParams): RetouchResult
}
