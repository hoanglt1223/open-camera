/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.preset

import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.model.StackCodec
import dev.opencamera.pipeline.looks.CubeParseException
import dev.opencamera.pipeline.looks.CubeParser
import dev.opencamera.pipeline.looks.CubeWriter
import dev.opencamera.pipeline.looks.LookDescriptor
import dev.opencamera.pipeline.looks.Lut3d
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** A saved subset of an edit. [groups] says which parts it sets; the rest of a photo's edit stays. */
data class Preset(
    val id: String,
    val name: String,
    val groups: Set<Group>,
    val stack: EditStack,
    val builtIn: Boolean = false,
) {
    /** Applies at [strength] (0..1): parameters interpolate, the look's intensity scales. */
    fun applyTo(target: EditStack, strength: Float = 1f): EditStack {
        val cleared = target.pasteFrom(EditStack(), groups)
        return if (strength >= 1f) target.pasteFrom(stack, groups) else cleared.blendToward(stack, groups, strength)
    }
}

class PresetFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** `.ocpreset`: JSON with the stack subset; user LUTs travel inline as `.cube` text. */
object PresetCodec {
    const val FORMAT = "opencamera-preset"
    const val EXTENSION = "ocpreset"
    private const val MAX_BYTES = 4 shl 20

    fun encode(p: Preset, lutCube: String? = null): String = JSONObject()
        .put("format", FORMAT)
        .put("version", 1)
        .put("name", p.name)
        .put("groups", JSONArray(p.groups.map { it.name }))
        .put("stack", StackCodec.toJson(p.stack.copy(source = null)))
        .putOpt("lut", lutCube)
        .toString(2)

    /** Returns the preset and an embedded LUT (if any). Rejects anything malformed. */
    fun decode(text: String, id: String): Pair<Preset, Lut3d?> {
        if (text.length > MAX_BYTES) throw PresetFormatException("Preset is too large")
        try {
            val o = JSONObject(text)
            if (o.optString("format") != FORMAT) throw PresetFormatException("Not an Open Camera preset")
            val groups = o.getJSONArray("groups").let { a ->
                (0 until a.length()).mapNotNull { runCatching { Group.valueOf(a.getString(it)) }.getOrNull() }.toSet()
            }
            val stack = StackCodec.fromJson(o.getJSONObject("stack")).subset(groups)
            val lut = o.optString("lut").ifBlank { null }?.let { CubeIo.parse(it) }
            return Preset(id, o.optString("name", "Preset").take(60), groups, stack) to lut
        } catch (e: JSONException) {
            throw PresetFormatException("Preset is not valid JSON", e)
        } catch (e: CubeParseException) {
            throw PresetFormatException("Preset LUT is invalid: ${e.message}", e)
        }
    }
}

/** `.cube` import/export. 3D files go through the look parser; 1D files become a 3D LUT. */
object CubeIo {
    private const val MAX_BYTES = 24 shl 20

    fun parse(text: String): Lut3d {
        if (text.length > MAX_BYTES) throw CubeParseException("LUT file is too large")
        val is1d = text.lineSequence().any { it.trim().uppercase().startsWith("LUT_1D_SIZE") }
        return if (is1d) parse1d(text) else CubeParser.parse(text)
    }

    fun write(lut: Lut3d, title: String) = CubeWriter.write(lut, title)

    private fun parse1d(text: String): Lut3d {
        var size = 0
        val values = ArrayList<Float>()
        var min = 0f
        var max = 1f
        text.lineSequence().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> Unit
                "LUT_1D_SIZE" -> {
                    size = parts.getOrNull(1)?.toIntOrNull() ?: throw CubeParseException("Line ${idx + 1}: invalid LUT_1D_SIZE")
                    if (size !in 2..65536) throw CubeParseException("Line ${idx + 1}: LUT_1D_SIZE $size out of range")
                }
                "LUT_1D_INPUT_RANGE" -> {
                    min = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    max = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
                    if (!(max > min)) throw CubeParseException("Line ${idx + 1}: bad input range")
                }
                "DOMAIN_MIN", "DOMAIN_MAX" -> Unit
                else -> {
                    if (parts.size != 3) throw CubeParseException("Line ${idx + 1}: expected 3 values")
                    for (p in parts) {
                        val v = p.toFloatOrNull()
                        if (v == null || !v.isFinite()) throw CubeParseException("Line ${idx + 1}: invalid number '$p'")
                        values += v.coerceIn(0f, 1f)
                    }
                }
            }
        }
        if (size == 0) throw CubeParseException("Missing LUT_1D_SIZE")
        if (values.size != size * 3) throw CubeParseException("Expected $size entries, found ${values.size / 3}")
        fun curve(c: Int, x: Float): Float {
            val pos = ((x - min) / (max - min)).coerceIn(0f, 1f) * (size - 1)
            val i = pos.toInt().coerceAtMost(size - 2)
            val f = pos - i
            return values[i * 3 + c] + (values[(i + 1) * 3 + c] - values[i * 3 + c]) * f
        }
        val n = 33
        val data = FloatArray(n * n * n * 3)
        var k = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            data[k++] = curve(0, r / (n - 1f))
            data[k++] = curve(1, g / (n - 1f))
            data[k++] = curve(2, b / (n - 1f))
        }
        return Lut3d(n, data)
    }
}

/**
 * Built-in presets (the camera looks plus a few parameter presets) and user presets stored as
 * `.ocpreset` files in [dir]. User LUT presets point at a user look; [looks] lists the looks.
 */
class PresetRepository(private val dir: File, private val looks: () -> List<LookDescriptor>) {
    fun builtIn(): List<Preset> {
        val fromLooks = looks().map { d ->
            Preset("look:${d.id}", d.name, setOf(Group.LOOK), EditStack(lookId = d.id), builtIn = !d.id.startsWith("user-"))
        }
        return fromLooks + PARAMETRIC
    }

    fun user(): List<Preset> = (dir.listFiles { f -> f.name.endsWith(".${PresetCodec.EXTENSION}") } ?: emptyArray())
        .sortedBy { order(it) }
        .mapNotNull { f -> runCatching { PresetCodec.decode(f.readText(), f.nameWithoutExtension).first }.getOrNull() }

    fun all() = builtIn() + user()

    fun save(name: String, groups: Set<Group>, from: EditStack): Preset {
        dir.mkdirs()
        val id = "p" + System.currentTimeMillis().toString(36)
        val p = Preset(id, name.trim().ifEmpty { "Preset" }.take(60), groups, from.subset(groups))
        File(dir, "$id.${PresetCodec.EXTENSION}").writeText(PresetCodec.encode(p))
        return p
    }

    fun delete(id: String) = File(dir, "$id.${PresetCodec.EXTENSION}").delete()

    fun rename(id: String, name: String): Boolean {
        val f = File(dir, "$id.${PresetCodec.EXTENSION}")
        val p = runCatching { PresetCodec.decode(f.readText(), id).first }.getOrNull() ?: return false
        f.writeText(PresetCodec.encode(p.copy(name = name.trim().take(60))))
        return true
    }

    /** Moves a preset one place up or down in the user list. */
    fun move(id: String, delta: Int) {
        val list = user().map { it.id }.toMutableList()
        val i = list.indexOf(id)
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        File(dir, ORDER).writeText(list.joinToString("\n"))
    }

    fun import(text: String, lutSaver: (String, Lut3d) -> String): Preset {
        val (preset, lut) = PresetCodec.decode(text, "")
        val stack = if (lut != null) preset.stack.copy(lookId = lutSaver(preset.name, lut)) else preset.stack
        return save(preset.name, if (lut != null) preset.groups + Group.LOOK else preset.groups, stack)
    }

    private fun order(f: File): Int {
        val ids = File(dir, ORDER).takeIf { it.isFile }?.readLines() ?: return Int.MAX_VALUE
        return ids.indexOf(f.nameWithoutExtension).let { if (it < 0) Int.MAX_VALUE else it }
    }

    companion object {
        private const val ORDER = "order.txt"

        val PARAMETRIC = listOf(
            Preset("bw-contrast", "B&W contrast", setOf(Group.COLOR, Group.LIGHT, Group.DETAIL, Group.HSL),
                EditStack().with(Params.SATURATION, -100f).with(Params.CONTRAST, 35f).with(Params.CLARITY, 15f)
                    .with(Params.hsl("blue", "lum"), -30f).with(Params.hsl("orange", "lum"), 15f), builtIn = true),
            Preset("matte", "Matte", setOf(Group.LIGHT, Group.CURVE, Group.COLOR),
                EditStack().with(Params.CONTRAST, -15f).with(Params.SATURATION, -10f)
                    .withCurve("rgb", listOf(CurvePoint(0f, 0.08f), CurvePoint(0.25f, 0.24f), CurvePoint(0.75f, 0.78f), CurvePoint(1f, 0.95f))),
                builtIn = true),
            Preset("warm-film", "Warm film", setOf(Group.COLOR, Group.EFFECTS, Group.GRADING),
                EditStack().with(Params.TEMP, 18f).with(Params.VIBRANCE, 10f).with(Params.GRAIN, 25f).with(Params.VIGNETTE, -15f)
                    .with(Params.grade("shadows", "hue"), 200f).with(Params.grade("shadows", "sat"), 12f)
                    .with(Params.grade("highlights", "hue"), 40f).with(Params.grade("highlights", "sat"), 15f), builtIn = true),
        )
    }
}

/** In-app clipboard for copy/paste of edits between photos. */
object EditClipboard {
    @Volatile
    var content: Pair<EditStack, Set<Group>>? = null
}
