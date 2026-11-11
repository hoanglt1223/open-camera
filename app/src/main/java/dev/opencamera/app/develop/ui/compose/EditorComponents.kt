/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencamera.app.ui.overlay.Glyph
import dev.opencamera.app.ui.overlay.Haptics
import dev.opencamera.app.ui.overlay.pressable
import dev.opencamera.app.ui.overlay.skin
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Line glyphs of the editor (24-unit grid, round strokes), drawn for this project. */
object EditorGlyphs {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun glyph(name: String, vararg paths: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val close = glyph("close", "M6 6l12 12M18 6 6 18")
    val undo = glyph("undo", "M9 14 4 9l5-5", "M4 9h10a6 6 0 0 1 0 12h-3")
    val redo = glyph("redo", "M15 14l5-5-5-5", "M20 9H10a6 6 0 0 0 0 12h3")
    val history = glyph("history", circle(12f, 12f, 9f), "M12 7v5l3 2")
    val compare = glyph("compare", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M12 4v16")
    val auto = glyph("auto", "M12 3l1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5z", "M18 15l.7 2.3L21 18l-2.3.7L18 21l-.7-2.3L15 18l2.3-.7z")
    val adjust = glyph("adjust", circle(12f, 12f, 9f), "M12 3v18")
    val looks = glyph("looks", circle(9f, 10f, 5f), circle(15f, 10f, 5f), circle(12f, 15f, 5f))
    val crop = glyph("crop", "M6 2v16h16", "M2 6h16v16")
    val retouch = glyph("retouch", "M4 20 15 9", "M15 4v2M19 5l-1.5 1.5M20 9h-2M17 3l1 1")
    val masks = glyph("masks", circle(12f, 12f, 9f), "M12 3a9 9 0 0 1 0 18z")
    val color = glyph("color", circle(12f, 12f, 9f), circle(8.5f, 10f, 1f), circle(12f, 7.5f, 1f), circle(15.5f, 10f, 1f))
    val export = glyph("export", "M12 15V3M7 8l5-5 5 5", "M5 13v7h14v-7")
}

/** 48 dp icon button; [onLongPress]/[onRelease] support press-and-hold actions. */
@Composable
fun EditorIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    onHold: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val s = skin
    val view = LocalView.current
    val click by rememberUpdatedState(onClick)
    val base = Modifier
        .size(48.dp)
        .semantics { contentDescription = description; role = Role.Button }
        .then(if (filled) Modifier.background(s.surface, CircleShape) else Modifier)
    val mod = if (onHold != null && enabled) base.pointerInput(Unit) {
        detectTapGestures(
            onTap = { Haptics.segment(view); click() },
            onPress = {
                val held = kotlinx.coroutines.withTimeoutOrNull(280) { tryAwaitRelease() }
                if (held == null) {
                    onHold(true)
                    tryAwaitRelease()
                    onHold(false)
                }
            },
        )
    } else base.pressable(enabled) { Haptics.segment(view); click() }
    Box(mod, contentAlignment = Alignment.Center) {
        Glyph(icon, if (enabled) s.fg else s.muted.copy(alpha = 0.4f), size = 22.dp)
    }
}

/** Chip with a dot when its value differs from the default; the selected chip is highlighted. */
@Composable
fun EditorChip(text: String, selected: Boolean, dirty: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val s = skin
    val view = LocalView.current
    val bg = if (selected && s.glass) s.chip else Color.Transparent
    val fg = when {
        !enabled -> s.muted.copy(alpha = 0.5f)
        selected -> if (s.glass) s.fg else s.accentText
        else -> s.muted
    }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics {
                this.selected = selected
                role = Role.Tab
                if (dirty) stateDescription = "•"
            }
            .pressable(enabled) { Haptics.segment(view); onClick() }
            .padding(vertical = 6.dp)
            .background(bg, RoundedCornerShape(s.radiusM))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(text, style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
            Box(Modifier.padding(top = 2.dp).size(4.dp).background(if (dirty) s.accent else Color.Transparent, CircleShape))
        }
    }
}

/** Horizontally scrolling chip row. */
@Composable
fun <T> ChipRow(items: List<T>, label: @Composable (T) -> String, selected: (T) -> Boolean, dirty: (T) -> Boolean = { false },
                enabled: (T) -> Boolean = { true }, onPick: (Int, T) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(items) { i, item ->
            EditorChip(label(item), selected(item), dirty(item), enabled(item)) { onPick(i, item) }
        }
    }
}

/**
 * iOS-style ruler dial: evenly spaced ticks, a fixed centre needle, a dot at the default value,
 * and the span from default to value tinted. Drag 2 dp per step; snaps to the default; double
 * tap resets. TalkBack sees a slider (range info + set progress).
 */
@Composable
fun RulerDial(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    default: Float,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = skin
    val view = LocalView.current
    var live by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) live = value }
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val dirty = abs(live - default) > step / 2
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            "$label ${format(live)}",
            style = TextStyle(color = if (dirty) s.accentText else s.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = format(live)
                    progressBarRangeInfo = ProgressBarRangeInfo(live, min..max, ((max - min) / step).roundToInt().coerceAtMost(1000))
                    setProgress { target ->
                        val v = target.coerceIn(min, max)
                        live = v; change(v); commit(); true
                    }
                }
                .pointerInput(min, max, step) {
                    detectTapGestures(onDoubleTap = {
                        live = default
                        change(default); commit()
                        Haptics.shutter(view)
                    })
                }
                .pointerInput(min, max, step) {
                    val pxPerStep = 2.dp.toPx()
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; acc = 0f },
                        onDragEnd = { dragging = false; commit() },
                        onDragCancel = { dragging = false; commit() },
                    ) { ch, dx ->
                        ch.consume()
                        acc -= dx
                        val steps = (acc / pxPerStep).toInt()
                        if (steps == 0) return@detectHorizontalDragGestures
                        acc -= steps * pxPerStep
                        val before = live
                        var next = (before + steps * step).coerceIn(min, max)
                        // Snap to the default when passing it.
                        if ((before - default) * (next - default) < 0f) next = default
                        if (next == before) return@detectHorizontalDragGestures
                        val tickBefore = floor((before - min) / (step * 10))
                        val tickAfter = floor((next - min) / (step * 10))
                        if (next == default) Haptics.shutter(view) else if (tickBefore != tickAfter) Haptics.tick(view)
                        live = next
                        change(next)
                    }
                },
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val pxPerStep = 2.dp.toPx()
            val tickEvery = 5
            val tickValue = step * tickEvery
            val halfSpan = cx / pxPerStep * step
            val first = kotlin.math.ceil((live - halfSpan - min) / tickValue).toInt().coerceAtLeast(0)
            val last = floor((live + halfSpan - min) / tickValue).toInt().coerceAtMost(((max - min) / tickValue).roundToInt())
            val defX = cx + (default - live) / step * pxPerStep
            for (k in first..last) {
                val v = min + k * tickValue
                val x = cx + (v - live) / step * pxPerStep
                val major = k % 5 == 0
                val inSpan = (v - default) * (live - default) >= 0f && abs(v - default) <= abs(live - default)
                val c = if (inSpan && dirty) s.accent else s.fg.copy(alpha = if (major) 0.7f else 0.35f)
                val h = if (major) size.height * 0.5f else size.height * 0.3f
                drawLine(c, Offset(x, cy - h / 2), Offset(x, cy + h / 2), strokeWidth = 1.dp.toPx())
            }
            if (defX in 0f..size.width) drawCircle(s.fg.copy(alpha = 0.8f), 2.5.dp.toPx(), Offset(defX, cy - size.height * 0.36f))
            drawLine(s.accent, Offset(cx, cy - size.height * 0.36f), Offset(cx, cy + size.height * 0.36f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** Formats a slider value as the editor shows it (signed, unit per parameter). */
fun formatValue(v: Float, step: Float, unit: String = ""): String {
    val text = when {
        step < 0.05f -> String.format(java.util.Locale.getDefault(), "%.2f", v)
        step < 0.5f -> String.format(java.util.Locale.getDefault(), "%.1f", v)
        else -> v.roundToInt().toString()
    }
    return (if (v > 0f && !text.startsWith("+")) "+$text" else text) + unit
}

/** Dock title + small action row used by panels. */
@Composable
fun PanelLabel(text: String) {
    BasicText(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = TextStyle(color = skin.muted, fontSize = 12.sp))
}

/** Bottom sheet over a scrim: tapping the scrim closes it. */
@Composable
fun EditorSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val s = skin
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(s.sheet, RoundedCornerShape(topStart = s.radiusL, topEnd = s.radiusL))
                .border(1.dp, s.stroke, RoundedCornerShape(topStart = s.radiusL, topEnd = s.radiusL))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, Modifier.weight(1f), style = TextStyle(color = s.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                EditorIcon(EditorGlyphs.close, title) { onClose() }
            }
            content()
        }
    }
}

