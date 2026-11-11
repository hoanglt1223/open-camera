/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class StackFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * JSON form of [EditStack] (`schemaVersion` 2). Version 1 stacks (the retouch editor's list
 * of steps) are migrated on read; unknown parameter keys are kept so a newer stack survives a
 * round trip through an older build.
 */
object StackCodec {
    fun encode(stack: EditStack): String = toJson(stack).toString()

    fun toJson(stack: EditStack): JSONObject = JSONObject().apply {
        put("schemaVersion", EditStack.SCHEMA_VERSION)
        stack.source?.let {
            put("source", JSONObject().put("mediaId", it.mediaId).put("hash", it.hash).putOpt("name", it.displayName))
        }
        put("values", JSONObject().also { o -> stack.values.toSortedMap().forEach { (k, v) -> o.put(k, v.toDouble()) } })
        if (stack.curves.isNotEmpty()) {
            put("curves", JSONObject().also { o ->
                stack.curves.forEach { (ch, pts) ->
                    o.put(ch, JSONArray().also { a -> pts.forEach { p -> a.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) } })
                }
            })
        }
        putOpt("look", stack.lookId)
        if (stack.disabled.isNotEmpty()) put("disabled", JSONArray(stack.disabled.map { it.name }))
        if (stack.masks.isNotEmpty()) put("masks", JSONArray().also { a -> stack.masks.forEach { a.put(maskJson(it)) } })
        if (stack.tools.isNotEmpty()) {
            put("tools", JSONArray().also { a ->
                stack.tools.forEach { t ->
                    a.put(JSONObject().put("tool", t.toolId).put("params", JSONObject(t.params.ifBlank { "{}" }))
                        .put("files", JSONArray(t.files)).put("enabled", t.enabled))
                }
            })
        }
    }

    fun decode(json: String): EditStack = try {
        fromJson(JSONObject(json))
    } catch (e: JSONException) {
        throw StackFormatException("Edit stack is not valid JSON", e)
    }

    fun fromJson(o: JSONObject): EditStack {
        val version = o.optInt("schemaVersion", o.optInt("version", 1))
        if (version > EditStack.SCHEMA_VERSION) throw StackFormatException("Edit stack version $version is newer than this app")
        if (version < 2) return migrateV1(o)
        val values = HashMap<String, Float>()
        o.optJSONObject("values")?.let { v ->
            for (k in v.keys()) {
                val d = v.optDouble(k, Double.NaN)
                if (!d.isNaN()) values[k] = Params.clamp(k, d.toFloat())
            }
        }
        val curves = HashMap<String, List<CurvePoint>>()
        o.optJSONObject("curves")?.let { c ->
            for (ch in c.keys()) {
                val arr = c.getJSONArray(ch)
                val pts = List(arr.length()) { i ->
                    val p = arr.getJSONArray(i)
                    CurvePoint(p.getDouble(0).toFloat().coerceIn(0f, 1f), p.getDouble(1).toFloat().coerceIn(0f, 1f))
                }.sortedBy { it.x }.distinctBy { it.x }
                if (pts.size >= 2) curves[ch] = pts
            }
        }
        val source = o.optJSONObject("source")?.let {
            SourceRef(it.getLong("mediaId"), it.getString("hash"), it.optString("name").ifBlank { null })
        }
        val disabled = o.optJSONArray("disabled")?.let { a ->
            (0 until a.length()).mapNotNull { runCatching { Group.valueOf(a.getString(it)) }.getOrNull() }.toSet()
        } ?: emptySet()
        val masks = o.optJSONArray("masks")?.let { a -> List(a.length()) { maskFrom(a.getJSONObject(it)) } } ?: emptyList()
        val tools = o.optJSONArray("tools")?.let { a ->
            List(a.length()) { i ->
                val t = a.getJSONObject(i)
                ToolStep(
                    t.getString("tool"), t.optJSONObject("params")?.toString() ?: "{}",
                    t.optJSONArray("files")?.let { f -> List(f.length()) { f.getString(it) } } ?: emptyList(),
                    t.optBoolean("enabled", true),
                )
            }
        } ?: emptyList()
        return EditStack(values, curves, o.optString("look").ifBlank { null }, masks, tools, disabled, source)
    }

    /**
     * Version 1 is a flat, ordered list of steps `{"tool": id, "params": {...}}`. White balance
     * and geometry steps map onto develop sliders; every other step (healing, object removal,
     * beauty, ...) is kept as a [ToolStep] for its tool to render.
     */
    private fun migrateV1(o: JSONObject): EditStack {
        // The retouch screen's RetouchStack: {"version":1,"applied":n,"ops":[{tool, params}]}.
        // Its params are RetouchParams (gains, keystone, lens) that only the retouch tools can
        // render, so every applied op stays a ToolStep, including whitebalance and geometry.
        o.optJSONArray("ops")?.let { ops ->
            val applied = o.optInt("applied", ops.length()).coerceIn(0, ops.length())
            return EditStack(tools = (0 until applied).map { i ->
                val s = ops.getJSONObject(i)
                ToolStep(s.getString("tool"), (s.optJSONObject("params") ?: JSONObject()).toString())
            })
        }
        val steps = o.optJSONArray("steps") ?: o.optJSONArray("tools") ?: JSONArray()
        var stack = EditStack()
        val tools = ArrayList<ToolStep>()
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val id = s.optString("tool", s.optString("id"))
            val p = s.optJSONObject("params") ?: JSONObject()
            when (id) {
                "wb", "white_balance" -> stack = stack.with(Params.TEMP, p.optDouble("temperature", 0.0).toFloat())
                    .with(Params.TINT, p.optDouble("tint", 0.0).toFloat())
                "rotate", "geometry" -> {
                    stack = stack.with(Params.CROP_ANGLE, p.optDouble("angle", 0.0).toFloat())
                        .with(Params.CROP_ROT90, (p.optInt("quarterTurns", 0) and 3).toFloat())
                    p.optJSONArray("crop")?.let { c ->
                        stack = stack.with(Params.CROP_LEFT, c.getDouble(0).toFloat()).with(Params.CROP_TOP, c.getDouble(1).toFloat())
                            .with(Params.CROP_RIGHT, c.getDouble(2).toFloat()).with(Params.CROP_BOTTOM, c.getDouble(3).toFloat())
                    }
                }
                "" -> Unit
                else -> tools += ToolStep(
                    id, p.toString(),
                    s.optJSONArray("files")?.let { f -> List(f.length()) { f.getString(it) } } ?: emptyList(),
                )
            }
        }
        return stack.copy(tools = tools)
    }

    private fun maskJson(m: LocalMask) = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("enabled", m.enabled)
        put("opacity", m.opacity.toDouble())
        put("values", JSONObject().also { o -> m.values.forEach { (k, v) -> o.put(k, v.toDouble()) } })
        put("components", JSONArray().also { a ->
            m.components.forEach { c -> a.put(JSONObject().put("op", c.op.name).put("invert", c.invert).put("shape", shapeJson(c.shape))) }
        })
    }

    private fun maskFrom(o: JSONObject): LocalMask {
        val values = HashMap<String, Float>()
        o.optJSONObject("values")?.let { v -> for (k in v.keys()) values[k] = v.getDouble(k).toFloat() }
        val comps = o.getJSONArray("components")
        return LocalMask(
            id = o.getString("id"),
            name = o.optString("name", "Mask"),
            components = List(comps.length()) { i ->
                val c = comps.getJSONObject(i)
                MaskComponent(MaskOp.valueOf(c.optString("op", "ADD")), shapeFrom(c.getJSONObject("shape")), c.optBoolean("invert"))
            },
            values = values,
            enabled = o.optBoolean("enabled", true),
            opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
        )
    }

    private fun shapeJson(s: MaskShape): JSONObject = JSONObject().put("type", s.type).apply {
        when (s) {
            is BrushShape -> put("strokes", JSONArray().also { a ->
                s.strokes.forEach { st ->
                    a.put(JSONObject().put("r", st.radius.toDouble()).put("feather", st.feather.toDouble())
                        .put("flow", st.flow.toDouble()).put("erase", st.erase)
                        .put("pts", JSONArray().also { p -> st.points.forEach { v -> p.put(round(v)) } }))
                }
            })
            is LinearShape -> put("x0", s.x0.toDouble()).put("y0", s.y0.toDouble()).put("x1", s.x1.toDouble()).put("y1", s.y1.toDouble())
            is RadialShape -> put("cx", s.cx.toDouble()).put("cy", s.cy.toDouble()).put("rx", s.rx.toDouble())
                .put("ry", s.ry.toDouble()).put("angle", s.angle.toDouble()).put("feather", s.feather.toDouble())
            is LuminanceRangeShape -> put("lo", s.lo.toDouble()).put("hi", s.hi.toDouble()).put("smooth", s.smooth.toDouble())
            is ColorRangeShape -> put("hue", s.hue.toDouble()).put("sat", s.saturation.toDouble()).put("tolerance", s.tolerance.toDouble())
            is AiShape -> put("kind", s.kind).putOpt("file", s.file).putOpt("model", s.model)
        }
    }

    private fun shapeFrom(o: JSONObject): MaskShape {
        fun f(k: String, d: Double = 0.0) = o.optDouble(k, d).toFloat()
        return when (val type = o.getString("type")) {
            "brush" -> {
                val a = o.getJSONArray("strokes")
                BrushShape(List(a.length()) { i ->
                    val st = a.getJSONObject(i)
                    val p = st.getJSONArray("pts")
                    Stroke(
                        FloatArray(p.length()) { p.getDouble(it).toFloat() }, st.getDouble("r").toFloat(),
                        st.optDouble("feather", 0.5).toFloat(), st.optDouble("flow", 1.0).toFloat(), st.optBoolean("erase"),
                    )
                })
            }
            "linear" -> LinearShape(f("x0"), f("y0"), f("x1"), f("y1", 1.0))
            "radial" -> RadialShape(f("cx", .5), f("cy", .5), f("rx", .25), f("ry", .25), f("angle"), f("feather", .5))
            "luminance" -> LuminanceRangeShape(f("lo"), f("hi", 1.0), f("smooth", .1))
            "color" -> ColorRangeShape(f("hue"), f("sat", .5), f("tolerance", .3))
            "ai" -> AiShape(o.getString("kind"), o.optString("file").ifBlank { null }, o.optString("model").ifBlank { null })
            else -> throw StackFormatException("Unknown mask type $type")
        }
    }

    private fun round(v: Float) = Math.round(v * 100000.0) / 100000.0

    /** Deflate + base64, for embedding in XMP. */
    fun pack(stack: EditStack): String {
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(encode(stack).toByteArray()) }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun unpack(packed: String): EditStack {
        val raw = try {
            InflaterInputStream(Base64.getDecoder().decode(packed.trim()).inputStream()).use { it.readBytes() }
        } catch (e: Exception) {
            throw StackFormatException("Embedded edit stack is corrupt", e)
        }
        if (raw.size > MAX_PACKED) throw StackFormatException("Embedded edit stack is too large")
        return decode(String(raw))
    }

    private const val MAX_PACKED = 8 shl 20
}
