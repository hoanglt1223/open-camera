/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui.compose

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.develop.DevelopActivity
import dev.opencamera.app.develop.DevelopActivity.Mode
import dev.opencamera.app.develop.DevelopDialogs
import dev.opencamera.app.develop.DevelopPanels
import dev.opencamera.app.develop.auto.AutoAdjust
import dev.opencamera.app.develop.export.ExportFormat
import dev.opencamera.app.develop.export.ExportOptions
import dev.opencamera.app.develop.export.MetadataPolicy
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.preset.Preset
import dev.opencamera.app.develop.render.Geometry
import dev.opencamera.app.ui.overlay.Glyph
import dev.opencamera.app.ui.overlay.Haptics
import dev.opencamera.app.ui.overlay.pressable
import dev.opencamera.app.ui.overlay.skin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Sheet { NONE, HISTORY, EXPORT, MORE }

/** The skinned editor: photo, top bar, context panel and category dock (portrait or side columns). */
@Composable
fun EditorScreen(a: DevelopActivity) {
    val s = skin
    val ui = a.ui
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wide = maxWidth > maxHeight || minOf(maxWidth, maxHeight) >= 600.dp
        val top: @Composable () -> Unit = { TopBar(a, vertical = wide, onSheet = { sheet = it }) }
        val dock: @Composable () -> Unit = { Dock(a, onExport = { sheet = Sheet.EXPORT }) }
        if (wide) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(Modifier.width(56.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) { top() }
                PhotoArea(a, Modifier.weight(1f).fillMaxHeight().padding(if (s.glass) 0.dp else 8.dp))
                Column(Modifier.width(292.dp).fillMaxHeight().background(if (s.glass) s.surface else Color(0xFF121214)), verticalArrangement = Arrangement.Bottom) { dock() }
            }
        } else if (s.glass) {
            Box(Modifier.fillMaxSize()) {
                PhotoArea(a, Modifier.fillMaxSize())
                Column(Modifier.fillMaxWidth().safeDrawingPadding().padding(top = 8.dp)) { top() }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x8C000000), Color(0xE6000000))))
                        .safeDrawingPadding(),
                ) { dock() }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                top()
                PhotoArea(a, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.fillMaxWidth().background(Color(0xFF121214)).border(1.dp, s.stroke)) { dock() }
            }
        }
        when (sheet) {
            Sheet.HISTORY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { HistorySheet(a) { sheet = Sheet.NONE } }
            Sheet.EXPORT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { ExportSheet(a) { sheet = Sheet.NONE } }
            Sheet.MORE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { MoreSheet(a) { sheet = Sheet.NONE } }
            Sheet.NONE -> Unit
        }
        ui.grid.value?.let { RetouchGridOverlay(it) }
        androidx.compose.runtime.SideEffect {
            ui.onBack = {
                val g = ui.grid.value
                when {
                    g != null -> { if (g.zoomed >= 0) g.zoomed = -1 else g.onCancel(); true }
                    sheet != Sheet.NONE -> { sheet = Sheet.NONE; true }
                    else -> false
                }
            }
        }
    }
}

@Composable
private fun TopBar(a: DevelopActivity, vertical: Boolean, onSheet: (Sheet) -> Unit) {
    val s = skin
    val ui = a.ui
    val items: @Composable () -> Unit = {
        EditorIcon(EditorGlyphs.close, stringResource(R.string.editor_cancel), filled = s.glass) { a.persist(); a.finish() }
        EditorIcon(EditorGlyphs.undo, stringResource(R.string.develop_undo), enabled = ui.canUndo.value, filled = s.glass) {
            a.history.undo()?.let { a.onStackChanged(true) }
        }
        EditorIcon(EditorGlyphs.redo, stringResource(R.string.develop_redo), enabled = ui.canRedo.value, filled = s.glass) {
            a.history.redo()?.let { a.onStackChanged(true) }
        }
        if (ui.raw.value) RawBadge()
        EditorIcon(EditorGlyphs.history, stringResource(R.string.editor_history), filled = s.glass) { onSheet(Sheet.HISTORY) }
        EditorIcon(EditorGlyphs.compare, stringResource(R.string.editor_compare), filled = s.glass, onHold = { on -> if (on) a.startHold() else a.endHold() }) {
            a.toggleCompare()
        }
        EditorIcon(EditorGlyphs.adjust, stringResource(R.string.editor_more), filled = s.glass) { onSheet(Sheet.MORE) }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items()
            DoneButton { onSheet(Sheet.EXPORT) }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items()
            Spacer(Modifier.weight(1f))
            DoneButton { onSheet(Sheet.EXPORT) }
        }
    }
}

@Composable
private fun RawBadge() {
    val s = skin
    val d = stringResource(R.string.editor_raw_desc)
    Box(
        Modifier.heightIn(min = 48.dp).semantics { contentDescription = d },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            stringResource(R.string.editor_raw_badge),
            Modifier.border(1.5.dp, s.accentText, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = TextStyle(color = s.accentText, fontSize = 10.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    val s = skin
    val view = LocalView.current
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
            .semantics { role = Role.Button }
            .pressable { Haptics.segment(view); onClick() }
            .padding(vertical = 6.dp)
            .background(s.accentBrush, RoundedCornerShape(if (s.glass) 18.dp else 8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(stringResource(R.string.editor_done), style = TextStyle(color = Color(0xFF111111), fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

/** The GL preview (an existing View with its gestures) plus Compose overlays for retouch input. */
@Composable
private fun PhotoArea(a: DevelopActivity, modifier: Modifier) {
    val s = skin
    val ui = a.ui
    Box(modifier) {
        AndroidView(
            factory = { a.previewFrame.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        // Painted retouch strokes, in view coordinates of the current framing.
        val strokes = ui.retouchStrokes.toList()
        val live = ui.liveStroke.value
        val taps = ui.retouchTaps.toList()
        ui.revision.intValue
        if (strokes.isNotEmpty() || live.isNotEmpty() || taps.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val r = a.overlay.image
                val long = maxOf(r.width(), r.height())
                fun pt(p: dev.opencamera.pipeline.retouch.NPoint) = a.sourceToView(p.x, p.y).let { Offset(it[0], it[1]) }
                for (st in strokes + listOf(dev.opencamera.pipeline.retouch.Stroke(live, ui.retouchBrush.floatValue, ui.retouchErase.value))) {
                    if (st.points.isEmpty()) continue
                    val c = if (st.erase) Color(0x6600AAFF) else Color(0x73FF3B30)
                    val w = st.radius * long * 2
                    for (i in 1 until st.points.size) drawLine(c, pt(st.points[i - 1]), pt(st.points[i]), strokeWidth = w, cap = StrokeCap.Round)
                    if (st.points.size == 1) drawCircle(c, w / 2, pt(st.points[0]))
                }
                for (t in taps) drawCircle(s.accent, 10.dp.toPx(), pt(t), style = Stroke(2.dp.toPx()))
            }
        }
        if (ui.showingOriginal.value) {
            BasicText(
                stringResource(R.string.editor_original),
                Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(color = Color.White, fontSize = 13.sp),
            )
        }
        if (ui.busy.value || !ui.ready.value) {
            BasicText(
                ui.status.value.ifEmpty { stringResource(R.string.editor_working) },
                Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = TextStyle(color = Color.White, fontSize = 13.sp),
            )
        }
    }
}

private data class CategoryItem(val c: Category, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val CATEGORIES = listOf(
    CategoryItem(Category.AUTO, R.string.editor_cat_auto, EditorGlyphs.auto),
    CategoryItem(Category.ADJUST, R.string.editor_cat_adjust, EditorGlyphs.adjust),
    CategoryItem(Category.LOOKS, R.string.editor_cat_looks, EditorGlyphs.looks),
    CategoryItem(Category.CROP, R.string.editor_cat_crop, EditorGlyphs.crop),
    CategoryItem(Category.RETOUCH, R.string.editor_cat_retouch, EditorGlyphs.retouch),
    CategoryItem(Category.MASKS, R.string.editor_cat_masks, EditorGlyphs.masks),
    CategoryItem(Category.COLOR, R.string.editor_cat_color, EditorGlyphs.color),
    CategoryItem(Category.EXPORT, R.string.editor_cat_export, EditorGlyphs.export),
)

@Composable
private fun Dock(a: DevelopActivity, onExport: () -> Unit) {
    val ui = a.ui
    val view = LocalView.current
    val cat = ui.category.value
    val fade = skin.fast<Float>()
    // Swiping across the panel (outside dials and chip rows, which consume their drags) moves between tools.
    Column(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            var total = 0f
            detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                if (abs(total) > 80.dp.toPx()) {
                    val i = CATEGORIES.indexOfFirst { it.c == ui.category.value } + if (total < 0) 1 else -1
                    CATEGORIES.getOrNull(i)?.let { ui.category.value = it.c; Haptics.tick(view) }
                }
            }) { _, dx -> total += dx }
        },
    ) {
        if (ui.ready.value) {
            AnimatedContent(cat, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "panel") { c ->
                Column(Modifier.fillMaxWidth().heightIn(min = 120.dp), verticalArrangement = Arrangement.Bottom) {
                    when (c) {
                        Category.AUTO -> AutoPanel(a)
                        Category.ADJUST -> AdjustPanel(a)
                        Category.LOOKS -> LooksPanel(a)
                        Category.CROP -> CropPanel(a)
                        Category.RETOUCH -> RetouchPanel(a)
                        Category.MASKS -> MasksPanel(a)
                        Category.COLOR -> ColorPanel(a)
                        Category.EXPORT -> ExportInline(onExport)
                    }
                }
            }
        }
        CategoryBar(cat) { if (it == Category.EXPORT) onExport(); ui.category.value = it }
    }
    DisposableEffect(cat) {
        if (cat != Category.CROP && cat != Category.RETOUCH && cat != Category.MASKS && a.mode != Mode.NONE) a.switchMode(Mode.NONE)
        onDispose { }
    }
}

@Composable
private fun CategoryBar(selected: Category, onPick: (Category) -> Unit) {
    val s = skin
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem((CATEGORIES.indexOfFirst { it.c == selected } - 2).coerceAtLeast(0)) }
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = if (s.glass) 12.dp else 0.dp, vertical = 6.dp)
            .then(if (s.glass) Modifier.background(s.surface, RoundedCornerShape(32.dp)).border(1.dp, s.stroke, RoundedCornerShape(32.dp)) else Modifier),
        state = state,
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        items(CATEGORIES) { item ->
            val on = item.c == selected
            val view = LocalView.current
            val label = stringResource(item.label)
            Column(
                Modifier.widthIn(min = 58.dp).heightIn(min = 56.dp)
                    .semantics { this.selected = on; role = Role.Tab; contentDescription = label }
                    .pressable { Haptics.segment(view); onPick(item.c) }
                    .padding(4.dp)
                    .background(if (on && s.glass) s.chip else Color.Transparent, RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Glyph(item.icon, if (on) s.accentText else s.fg.copy(alpha = 0.85f), size = 20.dp)
                BasicText(label, style = TextStyle(color = if (on) (if (s.glass) s.fg else s.accentText) else s.muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                if (!s.glass) Box(Modifier.padding(top = 2.dp).size(4.dp).background(if (on) s.accent else Color.Transparent, CircleShape))
            }
        }
    }
}

// ---- shared helpers ----------------------------------------------------------------------------

@Composable
internal fun paramLabel(key: String): String = DevelopPanels.labelRes(key)?.let { stringResource(it) } ?: key

internal fun dialStep(key: String): Float {
    val p = Params[key] ?: return 1f
    return maxOf(p.step, if (p.max - p.min <= 10f) 0.02f else 1f)
}

/** A ruler for one global parameter, recording one history step per drag. */
@Composable
internal fun ParamDial(a: DevelopActivity, key: String, label: String = paramLabel(key), set: (EditStack, Float) -> EditStack = { st, v -> st.with(key, v) }) {
    val p = Params[key] ?: return
    a.ui.revision.intValue
    val step = dialStep(key)
    RulerDial(
        label = label, value = a.stack[key], min = p.min, max = p.max, step = step, default = p.default,
        format = { formatValue(it, step, if (key == Params.CROP_ANGLE) "°" else "") },
        onChange = { v -> a.change(set(a.stack, v), label, "slider-$key") },
        onCommit = { a.seal() },
    )
}

internal fun <T> bg(a: DevelopActivity, work: () -> T, done: (T) -> Unit) {
    a.lifecycleScope.launch {
        val r = withContext(Dispatchers.Default) { runCatching(work).getOrNull() }
        if (r != null) done(r)
    }
}

// ---- Auto --------------------------------------------------------------------------------------

@Composable
private fun AutoPanel(a: DevelopActivity) {
    a.ui.revision.intValue
    fun apply(values: Map<String, Float>, label: String) {
        if (values.isEmpty()) return
        a.change(a.stack.withAll(values), label)
        a.seal()
        a.toast(a.getString(R.string.develop_auto_done, values.size))
    }
    val px = { a.engine.proxyPixels() }
    val items = listOf(
        stringResource(R.string.develop_auto_all) to { px()?.let { p -> bg(a, { AutoAdjust.all(p, a.info?.wide == true) }) { apply(it, "Auto") } }; Unit },
        stringResource(R.string.develop_auto_tone) to { px()?.let { p -> bg(a, { AutoAdjust.tone(AutoAdjust.samples(p)) }) { apply(it, "Auto tone") } }; Unit },
        stringResource(R.string.develop_auto_wb) to { px()?.let { p -> bg(a, { AutoAdjust.whiteBalance(AutoAdjust.samples(p), a.info?.wide == true) }) { apply(it, "Auto white balance") } }; Unit },
        stringResource(R.string.develop_auto_straighten) to {
            px()?.let { p ->
                bg(a, { AutoAdjust.straighten(p, a.engine.proxyWidth, a.engine.proxyHeight) }) { angle ->
                    if (angle == 0f) a.toast(a.getString(R.string.develop_straighten_none)) else {
                        a.change(Geometry.fitCrop(a.stack.with(Params.CROP_ANGLE, angle), a.engine.proxyWidth, a.engine.proxyHeight), "Auto straighten"); a.seal()
                    }
                }
            }; Unit
        },
        stringResource(R.string.editor_auto_undo) to {
            if (a.history.steps.lastOrNull()?.startsWith("Auto") == true) { a.history.undo(); a.onStackChanged(true) }
        },
    )
    PanelLabel(stringResource(R.string.develop_auto_ai_unavailable))
    ChipRow(items, { it.first }, { false }) { _, it -> it.second() }
}

// ---- Adjust ------------------------------------------------------------------------------------

private val ADJUST_KEYS = listOf(
    Params.EXPOSURE, Params.CONTRAST, Params.HIGHLIGHTS, Params.SHADOWS, Params.WHITES, Params.BLACKS,
    Params.TEMP, Params.TINT, Params.VIBRANCE, Params.SATURATION, Params.CLARITY, Params.TEXTURE, Params.DEHAZE,
    Params.SHARPEN, Params.NR_LUMA, Params.NR_COLOR, Params.VIGNETTE, Params.GRAIN,
)

@Composable
private fun AdjustPanel(a: DevelopActivity) {
    var key by remember { mutableStateOf(Params.EXPOSURE) }
    a.ui.revision.intValue
    ParamDial(a, key)
    ChipRow(ADJUST_KEYS, { paramLabel(it) }, { it == key }, { a.stack.values.containsKey(it) }) { _, k -> key = k }
}

// ---- Looks -------------------------------------------------------------------------------------

@Composable
private fun LooksPanel(a: DevelopActivity) {
    a.ui.revision.intValue
    var presets by remember { mutableStateOf(runCatching { a.presets.all() }.getOrDefault(emptyList())) }
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    var current by remember { mutableStateOf<Preset?>(null) }
    var base by remember { mutableStateOf(a.stack) }
    var strength by remember { mutableStateOf(100f) }
    LaunchedEffect(presets) {
        for (p in listOf<Preset?>(null) + presets) {
            val id = p?.id ?: "none"
            if (id in thumbs) continue
            val stack = if (p == null) a.stack.copy(lookId = null) else p.applyTo(a.stack)
            val look = if (stack.lookId != null) withContext(Dispatchers.IO) { runCatching { a.looks.params(stack.lookId, 1f) }.getOrNull() } else null
            a.engine.post {
                val saved = a.engine.look
                a.engine.look = look
                a.engine.thumbnail(stack) { bmp -> if (bmp != null) a.runOnUiThread { thumbs[id] = bmp } }
                a.engine.post { a.engine.look = saved }
            }
        }
    }
    if (current != null) {
        RulerDial(stringResource(R.string.develop_preset_strength), strength, 0f, 100f, 1f, 100f, { "${it.roundToInt()}" },
            onChange = { v -> strength = v; current?.let { a.change(it.applyTo(base, v / 100f), "Preset strength", "preset-strength") } },
            onCommit = { a.seal() })
    } else {
        ParamDial(a, Params.LOOK_INTENSITY, stringResource(R.string.editor_look_intensity))
    }
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            LookTile(stringResource(R.string.editor_look_none), thumbs["none"], a.stack.lookId == null && current == null) {
                current = null; a.change(a.stack.copy(lookId = null), "No look"); a.seal()
            }
        }
        items(presets, key = { it.id }) { p ->
            LookTile(p.name, thumbs[p.id], current?.id == p.id) {
                base = a.stack.pasteFrom(EditStack(), p.groups)
                current = p
                strength = 100f
                a.change(p.applyTo(a.stack), "Preset ${p.name}")
                a.seal()
            }
        }
    }
    val dialogs = DevelopDialogs(a)
    val more = listOf(
        stringResource(R.string.develop_preset_save) to { dialogs.savePreset { presets = a.presets.all() } },
        stringResource(R.string.develop_preset_import) to { dialogs.importPreset { presets = a.presets.all() } },
        stringResource(R.string.develop_copy) to { dialogs.copyEdits() },
        stringResource(R.string.develop_paste) to { dialogs.pasteEdits() },
        stringResource(R.string.develop_batch) to { dialogs.pickBatch() },
    )
    ChipRow(more, { it.first }, { false }) { _, it -> it.second() }
}

@Composable
private fun LookTile(name: String, thumb: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    val s = skin
    val view = LocalView.current
    Column(
        Modifier.width(68.dp).semantics { contentDescription = name; this.selected = selected; role = Role.Button }
            .pressable { Haptics.segment(view); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(s.radiusS)).background(s.chip)
                .border(if (selected) 2.dp else 0.dp, if (selected) s.accent else Color.Transparent, RoundedCornerShape(s.radiusS)),
        ) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        BasicText(name, style = TextStyle(color = if (selected) s.accentText else s.muted, fontSize = 11.sp, textAlign = TextAlign.Center), maxLines = 1)
    }
}

// ---- Crop --------------------------------------------------------------------------------------

@Composable
private fun CropPanel(a: DevelopActivity) {
    DisposableEffect(Unit) {
        a.switchMode(Mode.CROP); a.render()
        onDispose { if (a.mode == Mode.CROP) { a.switchMode(Mode.NONE); a.render() } }
    }
    a.ui.revision.intValue
    val w = a.engine.proxyWidth
    val h = a.engine.proxyHeight
    var dial by remember { mutableStateOf(Params.CROP_ANGLE) }
    val dialLabel = when (dial) {
        Params.PERSPECTIVE_V -> stringResource(R.string.editor_vertical)
        Params.PERSPECTIVE_H -> stringResource(R.string.editor_horizontal)
        else -> stringResource(R.string.editor_straighten)
    }
    ParamDial(a, dial, dialLabel) { st, v -> Geometry.fitCrop(st.with(dial, v), w, h) }
    val ratios = listOf(0f, -1f, 1f, 4f / 5f, 3f / 2f, 16f / 9f, 9f / 16f)
    val names = listOf(stringResource(R.string.develop_crop_free), stringResource(R.string.develop_crop_original), "1:1", "4:5", "3:2", "16:9", "9:16")
    ChipRow(ratios.indices.toList(), { names[it] }, { ratios[it] == a.stack[Params.CROP_ASPECT] }) { i, _ ->
        a.change(Geometry.applyAspect(a.stack, w, h, ratios[i]), "Aspect"); a.seal(); a.updateOverlay()
    }
    val tools = listOf(
        Params.CROP_ANGLE to stringResource(R.string.editor_straighten),
        Params.PERSPECTIVE_V to stringResource(R.string.editor_vertical),
        Params.PERSPECTIVE_H to stringResource(R.string.editor_horizontal),
        "rotate" to stringResource(R.string.editor_rotate),
        "flipH" to stringResource(R.string.develop_flip_h),
        "flipV" to stringResource(R.string.develop_flip_v),
        "auto" to stringResource(R.string.editor_auto_level),
        "reset" to stringResource(R.string.editor_reset),
    )
    ChipRow(tools, { it.second }, { it.first == dial }, { a.stack.values.containsKey(it.first) }) { _, (id, _) ->
        when (id) {
            "rotate" -> {
                val st = a.stack.with(Params.CROP_ROT90, ((a.stack[Params.CROP_ROT90].toInt() + 1) and 3).toFloat())
                a.change(if (st[Params.CROP_ASPECT] == 0f) Geometry.fitCrop(st, w, h) else Geometry.applyAspect(st, w, h, st[Params.CROP_ASPECT]), "Rotate"); a.seal()
            }
            "flipH" -> { a.change(a.stack.with(Params.CROP_FLIP_H, 1f - a.stack[Params.CROP_FLIP_H]), "Flip"); a.seal() }
            "flipV" -> { a.change(a.stack.with(Params.CROP_FLIP_V, 1f - a.stack[Params.CROP_FLIP_V]), "Flip"); a.seal() }
            "auto" -> a.engine.proxyPixels()?.let { p ->
                bg(a, { AutoAdjust.straighten(p, w, h) }) { angle ->
                    if (angle == 0f) a.toast(a.getString(R.string.develop_straighten_none))
                    else { a.change(Geometry.fitCrop(a.stack.with(Params.CROP_ANGLE, angle), w, h), "Auto straighten"); a.seal() }
                }
            }
            "reset" -> { a.change(a.stack.pasteFrom(EditStack(), setOf(Group.GEOMETRY)), "Reset crop"); a.seal() }
            else -> dial = id
        }
        a.updateOverlay()
    }
}

@Composable
private fun ExportInline(onExport: () -> Unit) {
    LaunchedEffect(Unit) { onExport() }
    PanelLabel(stringResource(R.string.develop_export_title))
}

// ---- Sheets ------------------------------------------------------------------------------------

@Composable
private fun HistorySheet(a: DevelopActivity, onClose: () -> Unit) {
    val s = skin
    a.ui.revision.intValue
    val past = a.history.steps
    val future = a.history.futureSteps
    EditorSheet(stringResource(R.string.editor_history), onClose) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            // Newest first: future steps (dimmed), the current state, then older steps, then the original.
            itemsIndexed(future.reversed()) { i, label ->
                val redoCount = future.size - i
                HistoryRow(label, current = false, future = true) { repeat(redoCount) { a.history.redo() }; a.onStackChanged(true) }
            }
            itemsIndexed(past.reversed()) { i, label ->
                HistoryRow(label, current = i == 0, future = false) { repeat(i) { a.history.undo() }; a.onStackChanged(true) }
            }
            item {
                HistoryRow(stringResource(R.string.editor_history_original), current = past.isEmpty(), future = false) {
                    repeat(past.size) { a.history.undo() }; a.onStackChanged(true)
                }
            }
        }
        val d = DevelopDialogs(a)
        ChipRow(listOf(stringResource(R.string.editor_snapshots) to { d.snapshots() }), { it.first }, { false }) { _, it -> it.second() }
    }
}

@Composable
private fun HistoryRow(label: String, current: Boolean, future: Boolean, onClick: () -> Unit) {
    val s = skin
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { this.selected = current; role = Role.Button }
            .pressable { Haptics.segment(view); onClick() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (current) s.accent else Color.Transparent, CircleShape).border(1.dp, s.stroke, CircleShape))
        BasicText(historyLabel(label), Modifier.padding(start = 12.dp), style = TextStyle(color = (if (current) s.accentText else s.fg).copy(alpha = if (future) 0.4f else 1f), fontSize = 15.sp))
    }
}

@Composable
private fun ExportSheet(a: DevelopActivity, onClose: () -> Unit) {
    val info = a.info ?: return
    val formats = remember { ExportFormat.available().filter { it != ExportFormat.HEIF || android.os.Build.VERSION.SDK_INT >= 28 } }
    var format by remember { mutableStateOf(formats.first()) }
    var size by remember { mutableStateOf(0) }
    var quality by remember { mutableStateOf(92f) }
    var meta by remember { mutableStateOf(MetadataPolicy.STRIP_LOCATION) }
    var keep by remember { mutableStateOf(true) }
    val d = DevelopDialogs(a)
    fun options(share: Boolean) = ExportOptions(
        format = format, quality = quality.roundToInt(), longSide = size,
        metadata = if (share && meta == MetadataPolicy.KEEP_ALL) MetadataPolicy.STRIP_LOCATION else meta,
    )
    EditorSheet(stringResource(R.string.develop_export_title), onClose) {
        PanelLabel(stringResource(R.string.editor_format))
        ChipRow(formats, { it.name }, { it == format }) { _, f -> format = f }
        PanelLabel(stringResource(R.string.editor_size))
        val sizes = listOf(0, 4096, 2048, 1080)
        val orig = stringResource(R.string.develop_export_size_original)
        ChipRow(sizes, { if (it == 0) orig else "$it" }, { it == size }) { _, v -> size = v }
        if (format != ExportFormat.PNG) {
            RulerDial(stringResource(R.string.editor_quality), quality, 50f, 100f, 1f, 92f, { "${it.roundToInt()}" }, { quality = it }, {})
        }
        PanelLabel(stringResource(R.string.editor_metadata))
        val metaNames = listOf(R.string.develop_meta_keep, R.string.develop_meta_location, R.string.develop_meta_all).map { stringResource(it) }
        ChipRow(MetadataPolicy.entries.toList(), { metaNames[it.ordinal] }, { it == meta }) { _, m -> meta = m }
        ChipRow(listOf(stringResource(R.string.editor_keep_original)), { it }, { keep }) { _, _ -> keep = !keep }
        val actions = listOf(
            stringResource(R.string.editor_save) to {
                onClose()
                if (keep) d.run(info, options(false), share = false, overwrite = false) else d.confirmOverwrite(info, options(false))
            },
            stringResource(R.string.develop_share) to { onClose(); d.run(info, options(true), share = true, overwrite = false) },
            stringResource(R.string.editor_xmp_export) to { onClose(); d.exportXmp() },
            stringResource(R.string.editor_xmp_import) to { onClose(); d.importXmp() },
        )
        ChipRow(actions, { it.first }, { it == actions.first() }) { _, it -> it.second() }
    }
}

@Composable
private fun MoreSheet(a: DevelopActivity, onClose: () -> Unit) {
    val d = DevelopDialogs(a)
    EditorSheet(stringResource(R.string.editor_more), onClose) {
        val items = listOf(
            stringResource(R.string.editor_histogram) to { a.toggleHistogram() },
            stringResource(R.string.editor_clipping) to { a.toggleClipping() },
            stringResource(R.string.editor_snapshots) to { onClose(); d.snapshots() },
            stringResource(R.string.develop_copy) to { onClose(); d.copyEdits() },
            stringResource(R.string.develop_paste) to { onClose(); d.pasteEdits() },
            stringResource(R.string.develop_batch) to { onClose(); d.pickBatch() },
            stringResource(R.string.editor_xmp_export) to { onClose(); d.exportXmp() },
            stringResource(R.string.editor_xmp_import) to { onClose(); d.importXmp() },
            stringResource(R.string.develop_revert) to { onClose(); d.revert() },
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(items) { (label, act) -> HistoryRow(label, current = false, future = false) { act() } }
        }
    }
}

private val HISTORY_LABELS = mapOf(
    "Auto" to R.string.develop_auto_all, "Auto tone" to R.string.develop_auto_tone, "Auto white balance" to R.string.develop_auto_wb,
    "Auto straighten" to R.string.develop_auto_straighten, "Curve" to R.string.editor_curves, "Reset curve" to R.string.editor_curves,
    "Grading" to R.string.editor_grade, "HSL" to R.string.editor_hsl, "Brush" to R.string.develop_mask_brush,
    "Add mask" to R.string.editor_mask_new, "Delete mask" to R.string.editor_mask_delete, "Mask combine" to R.string.editor_cat_masks,
    "Mask on/off" to R.string.develop_mask_enabled, "Gradient" to R.string.editor_cat_masks, "Colour range" to R.string.develop_mask_color,
    "Range" to R.string.editor_cat_masks, "Crop" to R.string.editor_cat_crop, "Aspect" to R.string.editor_cat_crop,
    "Reset crop" to R.string.editor_cat_crop, "Rotate" to R.string.editor_rotate, "Flip" to R.string.editor_flip,
    "No look" to R.string.editor_look_none, "Preset strength" to R.string.develop_preset_strength,
)

/** History steps are recorded with stable English keys; known ones are shown in the UI language. */
@Composable
private fun historyLabel(label: String): String {
    HISTORY_LABELS[label]?.let { return stringResource(it) }
    if (label.startsWith("Preset ")) return stringResource(R.string.editor_cat_looks) + ": " + label.removePrefix("Preset ")
    if (label.startsWith("Mask ")) return stringResource(R.string.editor_cat_masks) + ": " + label.removePrefix("Mask ")
    return label
}