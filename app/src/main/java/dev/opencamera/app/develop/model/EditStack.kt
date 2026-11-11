/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.model

/** A control point of a tone curve, both coordinates in 0..1. */
data class CurvePoint(val x: Float, val y: Float)

enum class MaskOp { ADD, SUBTRACT, INTERSECT }

/**
 * Mask geometry. Coordinates are normalized (0..1) to the upright source image before crop,
 * so a mask renders identically at proxy and full resolution. Radii are relative to the long
 * side of the image.
 */
sealed interface MaskShape {
    val type: String
}

/** A brush stroke: polyline of normalized points; erase strokes remove coverage. */
data class Stroke(
    val points: FloatArray,
    val radius: Float,
    val feather: Float = 0.5f,
    val flow: Float = 1f,
    val erase: Boolean = false,
) {
    override fun equals(other: Any?) = other is Stroke && points.contentEquals(other.points) &&
        radius == other.radius && feather == other.feather && flow == other.flow && erase == other.erase

    override fun hashCode() = points.contentHashCode() * 31 + radius.hashCode()
}

data class BrushShape(val strokes: List<Stroke>) : MaskShape {
    override val type get() = "brush"
}

/** Full effect at (x0, y0), none at (x1, y1). */
data class LinearShape(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : MaskShape {
    override val type get() = "linear"
}

/** Ellipse with radii relative to the long side; effect inside fading over [feather]. */
data class RadialShape(
    val cx: Float, val cy: Float, val rx: Float, val ry: Float,
    val angle: Float = 0f, val feather: Float = 0.5f,
) : MaskShape {
    override val type get() = "radial"
}

/** Selects pixels whose source luminance (0..1) is in [lo, hi], with a soft edge [smooth]. */
data class LuminanceRangeShape(val lo: Float, val hi: Float, val smooth: Float = 0.1f) : MaskShape {
    override val type get() = "luminance"
}

/** Selects pixels near a sampled colour: hue in degrees, tolerance 0..1. */
data class ColorRangeShape(val hue: Float, val saturation: Float, val tolerance: Float = 0.3f) : MaskShape {
    override val type get() = "color"
}

/**
 * Segmentation mask. [kind] is subject, person, hair, faceSkin, bodySkin or sky; [file] is an
 * 8-bit PNG at proxy resolution inside the edit store, [model] the model id and hash it came from.
 */
data class AiShape(val kind: String, val file: String?, val model: String?) : MaskShape {
    override val type get() = "ai"
}

data class MaskComponent(val op: MaskOp, val shape: MaskShape, val invert: Boolean = false)

data class LocalMask(
    val id: String,
    val name: String,
    val components: List<MaskComponent>,
    /** Deltas for [Params.LOCAL] keys. */
    val values: Map<String, Float> = emptyMap(),
    val enabled: Boolean = true,
    val opacity: Float = 1f,
)

/**
 * A pluggable tool's step (retouch tools of the retouch editor, AI layers). The develop editor
 * stores it verbatim and asks the [dev.opencamera.app.develop.tools.ToolRegistry] to render it.
 */
data class ToolStep(
    val toolId: String,
    val params: String,
    val files: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/** Links a stack to its photo: MediaStore id plus the first 16 bytes of the SHA-256. */
data class SourceRef(val mediaId: Long, val hash: String, val displayName: String? = null)

/**
 * The non-destructive edit of one photo. Immutable; every edit produces a new stack, which is
 * what makes undo/redo and snapshots cheap. Only non-default slider values are stored.
 */
data class EditStack(
    val values: Map<String, Float> = emptyMap(),
    val curves: Map<String, List<CurvePoint>> = emptyMap(),
    val lookId: String? = null,
    val masks: List<LocalMask> = emptyList(),
    val tools: List<ToolStep> = emptyList(),
    val disabled: Set<Group> = emptySet(),
    val source: SourceRef? = null,
) {
    operator fun get(key: String): Float = values[key] ?: Params.default(key)

    /** Value as the renderer sees it: default when the parameter's group is switched off. */
    fun effective(key: String): Float {
        val group = Params[key]?.group
        return if (group != null && group in disabled) Params.default(key) else get(key)
    }

    fun with(key: String, value: Float): EditStack {
        val v = Params.clamp(key, value)
        val next = values.toMutableMap()
        if (v == Params.default(key)) next.remove(key) else next[key] = v
        return copy(values = next)
    }

    fun withAll(changes: Map<String, Float>): EditStack = changes.entries.fold(this) { s, (k, v) -> s.with(k, v) }

    fun curve(channel: String): List<CurvePoint> = curves[channel] ?: IDENTITY_CURVE

    fun withCurve(channel: String, points: List<CurvePoint>): EditStack {
        val sorted = points.sortedBy { it.x }.map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .distinctBy { it.x }
        val next = curves.toMutableMap()
        if (sorted.size < 2 || sorted == IDENTITY_CURVE) next.remove(channel) else next[channel] = sorted
        return copy(curves = next)
    }

    val isIdentity: Boolean
        get() = values.isEmpty() && curves.isEmpty() && lookId == null && masks.isEmpty() && tools.isEmpty()

    /** The parts of this stack that belong to [groups] (masks and tools are not grouped). */
    fun subset(groups: Set<Group>, includeMasks: Boolean = false): EditStack = EditStack(
        values = values.filterKeys { Params[it]?.group in groups },
        curves = if (Group.CURVE in groups) curves else emptyMap(),
        lookId = if (Group.LOOK in groups) lookId else null,
        masks = if (includeMasks) masks else emptyList(),
    )

    /** Replaces [groups] of this stack with those of [other] (paste, preset apply). */
    fun pasteFrom(other: EditStack, groups: Set<Group>, includeMasks: Boolean = false): EditStack {
        val kept = values.filterKeys { Params[it]?.group !in groups }
        return copy(
            values = kept + other.values.filterKeys { Params[it]?.group in groups },
            curves = if (Group.CURVE in groups) other.curves else curves,
            lookId = if (Group.LOOK in groups) other.lookId else lookId,
            masks = if (includeMasks) other.masks else masks,
        )
    }

    /**
     * Preset strength: moves every value of [preset] from this stack's value toward the preset
     * by [amount] (0..1). Curves blend point-wise through sampling; the look's own intensity
     * scales with [amount].
     */
    fun blendToward(preset: EditStack, groups: Set<Group>, amount: Float): EditStack {
        val t = amount.coerceIn(0f, 1f)
        var out = this
        val keys = (preset.values.keys + values.keys).filter { Params[it]?.group in groups }
        for (k in keys) out = out.with(k, get(k) + (preset[k] - get(k)) * t)
        if (Group.CURVE in groups) {
            for (ch in (preset.curves.keys + curves.keys)) {
                val a = curve(ch)
                val b = preset.curve(ch)
                val xs = (a.map { it.x } + b.map { it.x }).distinct().sorted()
                out = out.withCurve(ch, xs.map { x -> CurvePoint(x, lerpCurve(a, x) + (lerpCurve(b, x) - lerpCurve(a, x)) * t) })
            }
        }
        if (Group.LOOK in groups && preset.lookId != null) {
            out = out.copy(lookId = preset.lookId)
                .with(Params.LOOK_INTENSITY, preset[Params.LOOK_INTENSITY] * t)
        }
        return out
    }

    companion object {
        const val SCHEMA_VERSION = 2
        val IDENTITY_CURVE = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val CURVE_CHANNELS = listOf("rgb", "r", "g", "b")

        private fun lerpCurve(points: List<CurvePoint>, x: Float): Float {
            if (x <= points.first().x) return points.first().y
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                if (x <= b.x) return a.y + (b.y - a.y) * ((x - a.x) / (b.x - a.x).coerceAtLeast(1e-6f))
            }
            return points.last().y
        }
    }
}
