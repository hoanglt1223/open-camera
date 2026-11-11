/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.opencamera.app.R
import dev.opencamera.app.develop.DevelopActivity
import dev.opencamera.app.develop.DevelopDialogs
import dev.opencamera.app.develop.DevelopPanels
import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.ui.overlay.Haptics
import dev.opencamera.app.ui.overlay.pressable
import dev.opencamera.app.ui.overlay.skin
import dev.opencamera.pipeline.looks.ToneTable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class ColorTool { CURVES, HSL, GRADE }

@Composable
internal fun ColorPanel(a: DevelopActivity) {
    var tool by remember { mutableStateOf(ColorTool.CURVES) }
    a.ui.revision.intValue
    when (tool) {
        ColorTool.CURVES -> CurvesTool(a)
        ColorTool.HSL -> HslTool(a)
        ColorTool.GRADE -> GradeTool(a)
    }
    val names = mapOf(
        ColorTool.CURVES to stringResource(R.string.editor_curves),
        ColorTool.HSL to stringResource(R.string.editor_hsl),
        ColorTool.GRADE to stringResource(R.string.editor_grade),
    )
    ChipRow(ColorTool.entries.toList(), { names.getValue(it) }, { it == tool }) { _, t -> tool = t }
}

private val CURVE_COLORS = listOf(Color.White, Color(0xFFFF6060), Color(0xFF60FF60), Color(0xFF6090FF))

@Composable
private fun CurvesTool(a: DevelopActivity) {
    val s = skin
    val view = LocalView.current
    var ch by remember { mutableIntStateOf(0) }
    val channel = EditStack.CURVE_CHANNELS[ch]
    val names = listOf(R.string.develop_curve_rgb, R.string.develop_curve_red, R.string.develop_curve_green, R.string.develop_curve_blue).map { stringResource(it) }
    a.ui.revision.intValue
    val points = a.stack.curve(channel)
    val desc = stringResource(R.string.editor_curve_desc, names[ch], points.size)
    Box(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 24.dp, vertical = 6.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(158.dp)
                .semantics { contentDescription = desc }
                .pointerInput(channel) {
                    val pad = 10.dp.toPx()
                    fun toPoint(o: Offset) = CurvePoint(((o.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f), (1f - (o.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f))
                    fun toView(p: CurvePoint) = Offset(pad + p.x * (size.width - 2 * pad), size.height - pad - p.y * (size.height - 2 * pad))
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var pts = a.stack.curve(channel)
                        var idx = pts.indices.minByOrNull { (toView(pts[it]) - down.position).getDistance() } ?: -1
                        if (idx >= 0 && (toView(pts[idx]) - down.position).getDistance() > 28.dp.toPx()) idx = -1
                        if (idx < 0 && pts.size < 16) {
                            val np = toPoint(down.position)
                            pts = (pts + np).sortedBy { it.x }
                            idx = pts.indexOf(np)
                            a.change(a.stack.withCurve(channel, pts), "Curve", "curve-$channel")
                            Haptics.segment(view)
                        }
                        val start = System.currentTimeMillis()
                        var moved = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            if (c.positionChange() != Offset.Zero) moved = true
                            c.consume()
                            if (idx < 0) continue
                            val p = toPoint(c.position)
                            val lo = if (idx == 0) 0f else pts[idx - 1].x + 0.01f
                            val hi = if (idx == pts.size - 1) 1f else pts[idx + 1].x - 0.01f
                            val x = if (idx == 0 || idx == pts.size - 1) pts[idx].x else p.x.coerceIn(lo, hi)
                            pts = pts.toMutableList().also { it[idx] = CurvePoint(x, p.y) }
                            a.change(a.stack.withCurve(channel, pts), "Curve", "curve-$channel")
                        }
                        // Long press without moving removes an inner point.
                        if (!moved && idx in 1 until pts.size - 1 && System.currentTimeMillis() - start > 500) {
                            a.change(a.stack.withCurve(channel, pts.filterIndexed { k, _ -> k != idx }), "Curve")
                            Haptics.longPress(view)
                        }
                        a.seal()
                    }
                },
        ) {
            val pad = 10.dp.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            for (i in 1..3) {
                drawLine(s.stroke, Offset(pad + i * w / 4, pad), Offset(pad + i * w / 4, pad + h), pathEffect = dash)
                drawLine(s.stroke, Offset(pad, pad + i * h / 4), Offset(pad + w, pad + i * h / 4), pathEffect = dash)
            }
            drawRect(s.stroke, Offset(pad, pad), androidx.compose.ui.geometry.Size(w, h), style = Stroke(1f))
            val table = ToneTable.build(points.map { it.x to it.y }, 0f)
            val path = Path()
            table.forEachIndexed { i, v ->
                val x = pad + i / 255f * w
                val y = pad + h - v * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, CURVE_COLORS[ch], style = Stroke(2.dp.toPx()))
            for (p in points) drawCircle(CURVE_COLORS[ch], 6.dp.toPx(), Offset(pad + p.x * w, pad + h - p.y * h))
        }
    }
    val tools = names.indices.map { it.toString() } + listOf("points", "reset")
    ChipRow(tools, {
        when (it) {
            "points" -> stringResource(R.string.develop_curve_points)
            "reset" -> stringResource(R.string.editor_reset)
            else -> names[it.toInt()]
        }
    }, { it == ch.toString() }, { it.toIntOrNull()?.let { i -> a.stack.curves.containsKey(EditStack.CURVE_CHANNELS[i]) } ?: false }) { _, id ->
        when (id) {
            "points" -> DevelopDialogs(a).curvePoints(channel) { a.ui.revision.intValue++ }
            "reset" -> { a.change(a.stack.withCurve(channel, EditStack.IDENTITY_CURVE), "Reset curve"); a.seal() }
            else -> ch = id.toInt()
        }
    }
}

@Composable
private fun HslTool(a: DevelopActivity) {
    val s = skin
    val view = LocalView.current
    var band by remember { mutableIntStateOf(0) }
    var what by remember { mutableStateOf("hue") }
    val bandNames = DevelopPanels.BAND_LABELS.map { stringResource(it) }
    a.ui.revision.intValue
    val key = Params.hsl(Params.HSL_BANDS[band], what)
    ParamDial(a, key, bandNames[band] + " · " + stringResource(if (what == "hue") R.string.editor_hue else if (what == "sat") R.string.editor_sat else R.string.editor_lum))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Params.HSL_BANDS.forEachIndexed { i, b ->
            val on = i == band
            val dirty = listOf("hue", "sat", "lum").any { a.stack.values.containsKey(Params.hsl(b, it)) }
            Box(
                Modifier.size(48.dp).semantics { contentDescription = bandNames[i]; selected = on; role = Role.RadioButton }
                    .pressable { Haptics.segment(view); band = i },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(if (on) 28.dp else 22.dp).background(Color(DevelopPanels.BAND_COLORS[i]), CircleShape)
                        .border(if (on) 2.dp else if (dirty) 1.dp else 0.dp, if (on) s.fg else s.accent, CircleShape),
                )
            }
        }
    }
    val opts = listOf("hue" to R.string.editor_hue, "sat" to R.string.editor_sat, "lum" to R.string.editor_lum)
    ChipRow(opts, { stringResource(it.second) }, { it.first == what }) { _, o -> what = o.first }
}

@Composable
private fun GradeTool(a: DevelopActivity) {
    var range by remember { mutableIntStateOf(0) }
    var dial by remember { mutableStateOf("lum") }
    val rangeNames = DevelopPanels.RANGE_LABELS.map { stringResource(it) }
    a.ui.revision.intValue
    val dialKey = when (dial) {
        "blend" -> Params.GRADE_BLENDING
        "balance" -> Params.GRADE_BALANCE
        else -> Params.grade(Params.GRADE_RANGES[range], "lum")
    }
    ParamDial(a, dialKey, if (dial == "lum") rangeNames[range] + " · " + stringResource(R.string.editor_lum) else paramLabel(dialKey))
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (i in 0 until 3) GradeWheel(a, i, rangeNames[i], selected = range == i) { range = i; dial = "lum" }
    }
    val opts = listOf("lum" to stringResource(R.string.editor_lum), "global" to rangeNames[3], "blend" to paramLabel(Params.GRADE_BLENDING),
        "balance" to paramLabel(Params.GRADE_BALANCE), "enter" to stringResource(R.string.develop_grade_enter))
    ChipRow(opts, { it.second }, { (it.first == dial && it.first != "global") || (it.first == "global" && range == 3) }) { _, (id, _) ->
        when (id) {
            "global" -> { range = 3; dial = "lum" }
            "enter" -> DevelopDialogs(a).gradeValues(Params.GRADE_RANGES[range]) { a.ui.revision.intValue++ }
            else -> dial = id
        }
    }
}

/** Hue = angle, saturation = distance from the centre. Double tap resets. */
@Composable
private fun GradeWheel(a: DevelopActivity, index: Int, name: String, selected: Boolean, onSelect: () -> Unit) {
    val s = skin
    val view = LocalView.current
    val r = Params.GRADE_RANGES[index]
    val hue = a.stack[Params.grade(r, "hue")]
    val sat = a.stack[Params.grade(r, "sat")]
    val desc = stringResource(R.string.editor_wheel_desc, name, hue.roundToInt(), sat.roundToInt())
    Canvas(
        Modifier.size(84.dp)
            .semantics { contentDescription = desc; this.selected = selected; role = Role.Button }
            .pointerInput(index) { detectTapGestures(onDoubleTap = {
                a.change(a.stack.with(Params.grade(r, "hue"), 0f).with(Params.grade(r, "sat"), 0f), "Grading"); a.seal(); Haptics.shutter(view)
            }, onTap = { onSelect() }) }
            .pointerInput(index) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSelect()
                    fun set(o: Offset) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = o.x - cx
                        val dy = o.y - cy
                        val h = ((Math.toDegrees(atan2(-dy, dx).toDouble()) + 360) % 360).toFloat()
                        val sv = (hypot(dx, dy) / (minOf(cx, cy)) * 100f).coerceIn(0f, 100f)
                        a.change(a.stack.with(Params.grade(r, "hue"), h).with(Params.grade(r, "sat"), sv), "Grading", "wheel-$r")
                    }
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        if (c.positionChange() != Offset.Zero) { moved = true; c.consume(); set(c.position) }
                    }
                    if (moved) a.seal()
                    down.consume()
                }
            },
    ) {
        val rad = size.minDimension / 2 - 2.dp.toPx()
        val hues = (0..12).map { Color.hsv((360f - it * 30f) % 360f, 1f, 1f) }
        drawCircle(Brush.sweepGradient(hues), rad)
        drawCircle(Brush.radialGradient(listOf(Color.Gray, Color.Transparent), radius = rad), rad)
        drawCircle(if (selected) s.accent else s.stroke, rad, style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()))
        val a2 = Math.toRadians(hue.toDouble())
        val d = sat / 100f * rad
        // The sweep runs clockwise on screen, so its colours are listed in reverse to make hue counter-clockwise.
        drawCircle(Color.White, 5.dp.toPx(), Offset(center.x + (cos(a2) * d).toFloat(), center.y - (sin(a2) * d).toFloat()), style = Stroke(2.dp.toPx()))
    }
}
