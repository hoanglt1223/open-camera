/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencamera.app.R
import dev.opencamera.app.pro.ExposureMath
import dev.opencamera.app.pro.MeteringMode
import dev.opencamera.app.pro.ProCapabilities
import dev.opencamera.app.pro.ProRanges
import dev.opencamera.app.pro.ProSettings
import dev.opencamera.app.pro.WbMode
import dev.opencamera.app.pro.clampTo
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ProDial { ISO, SHUTTER, EV, WB, FOCUS, METER }

/**
 * Value stops of one Pro dial and how a stop maps back to [ProSettings]; the same rules as
 * the View panel (auto first, manual exposure needs both ISO and shutter).
 */
class DialModel(val dial: ProDial, private val caps: ProCapabilities, private val labels: Labels) {
    class Labels(
        val auto: String, val infinity: String, val wb: List<String>, val meter: List<String>,
    )

    private val isoStops = caps.ranges.iso?.let { ExposureMath.isoStops(it) }.orEmpty()
    private val shutterStops = caps.ranges.exposureNs?.let { ExposureMath.shutterStops(it) }.orEmpty()
    private val evRange = caps.ranges.ev

    val count: Int = when (dial) {
        ProDial.ISO -> 1 + isoStops.size
        ProDial.SHUTTER -> 1 + shutterStops.size
        ProDial.EV -> evRange.last - evRange.first + 1
        ProDial.WB -> 1 + WB_PRESETS.size + (ProRanges.KELVIN_MAX - ProRanges.KELVIN_MIN) / KELVIN_STEP + 1
        ProDial.FOCUS -> 1 + FOCUS_STEPS + 1
        ProDial.METER -> MeteringMode.entries.size
    }

    fun label(i: Int): String = when (dial) {
        ProDial.ISO -> if (i == 0) labels.auto else isoStops[i - 1].toString()
        ProDial.SHUTTER -> if (i == 0) labels.auto else ExposureMath.formatShutter(shutterStops[i - 1])
        ProDial.EV -> ExposureMath.formatEv((evRange.first + i) * caps.evStep)
        ProDial.WB -> when {
            i == 0 -> labels.auto
            i <= WB_PRESETS.size -> labels.wb[i - 1]
            else -> "${kelvinAt(i)}K"
        }
        ProDial.FOCUS -> when {
            i == 0 -> labels.auto
            else -> ExposureMath.dioptersToMeters(dioptersAt(i))?.let { "%.2fm".format(it) } ?: labels.infinity
        }
        ProDial.METER -> labels.meter[i]
    }

    fun index(s: ProSettings): Int = when (dial) {
        ProDial.ISO -> if (!s.manualExposure) 0 else 1 + ExposureMath.nearestIndex(isoStops, s.iso!!)
        ProDial.SHUTTER -> if (!s.manualExposure) 0 else 1 + ExposureMath.nearestIndex(shutterStops, s.exposureNs!!)
        ProDial.EV -> s.evIndex - evRange.first
        ProDial.WB -> when (s.wbMode) {
            WbMode.AUTO -> 0
            WbMode.KELVIN -> 1 + WB_PRESETS.size + (s.kelvin - ProRanges.KELVIN_MIN) / KELVIN_STEP
            else -> 1 + WB_PRESETS.indexOf(s.wbMode)
        }
        ProDial.FOCUS -> s.focusDiopters?.let {
            // Index 1 is infinity (0 diopters), the last index the closest focus.
            1 + ((it / caps.ranges.minFocusDiopters) * FOCUS_STEPS).roundToInt()
        } ?: 0
        ProDial.METER -> s.metering.ordinal
    }.coerceIn(0, count - 1)

    fun apply(s: ProSettings, i: Int): ProSettings = when (dial) {
        ProDial.ISO -> if (i == 0) s.copy(iso = null, exposureNs = null)
        else s.copy(iso = isoStops[i - 1], exposureNs = s.exposureNs ?: DEFAULT_EXPOSURE_NS)
        ProDial.SHUTTER -> if (i == 0) s.copy(iso = null, exposureNs = null)
        else s.copy(iso = s.iso ?: DEFAULT_ISO, exposureNs = shutterStops[i - 1])
        ProDial.EV -> s.copy(evIndex = evRange.first + i)
        ProDial.WB -> when {
            i == 0 -> s.copy(wbMode = WbMode.AUTO)
            i <= WB_PRESETS.size -> s.copy(wbMode = WB_PRESETS[i - 1])
            else -> s.copy(wbMode = WbMode.KELVIN, kelvin = kelvinAt(i))
        }
        ProDial.FOCUS -> s.copy(focusDiopters = if (i == 0) null else dioptersAt(i))
        ProDial.METER -> s.copy(metering = MeteringMode.entries[i])
    }.clampTo(caps.ranges)

    /** Short value for the tab under the dial name. */
    fun summary(s: ProSettings) = label(index(s))

    private fun kelvinAt(i: Int) = ProRanges.KELVIN_MIN + (i - 1 - WB_PRESETS.size) * KELVIN_STEP
    private fun dioptersAt(i: Int) = (i - 1).toFloat() / FOCUS_STEPS * caps.ranges.minFocusDiopters

    companion object {
        val WB_PRESETS = listOf(WbMode.INCANDESCENT, WbMode.FLUORESCENT, WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.SHADE)
        const val KELVIN_STEP = 100
        const val FOCUS_STEPS = 50
        const val DEFAULT_ISO = 400
        const val DEFAULT_EXPOSURE_NS = 16_666_667L

        fun available(dial: ProDial, c: ProCapabilities) = when (dial) {
            ProDial.ISO, ProDial.SHUTTER -> c.manualExposure
            ProDial.FOCUS -> c.manualFocus
            ProDial.EV -> c.ranges.ev.first < c.ranges.ev.last
            ProDial.METER -> c.maxAeRegions > 0
            ProDial.WB -> true
        }
    }
}

@Composable
fun ProPanel(pro: ProUiState, actions: CameraUiActions) {
    CountRecompositions("ProPanel")
    val s = skin
    val caps = pro.caps ?: return
    val context = LocalContext.current
    val labels = remember(context) {
        DialModel.Labels(
            auto = context.getString(R.string.pro_auto),
            infinity = context.getString(R.string.pro_focus_infinity),
            wb = listOf(
                R.string.pro_wb_incandescent, R.string.pro_wb_fluorescent, R.string.pro_wb_daylight,
                R.string.pro_wb_cloudy, R.string.pro_wb_shade,
            ).map(context::getString),
            meter = listOf(R.string.pro_meter_center, R.string.pro_meter_spot, R.string.pro_meter_matrix).map(context::getString),
        )
    }
    val dials = remember(caps) { ProDial.entries.filter { DialModel.available(it, caps) }.map { DialModel(it, caps, labels) } }
    var selected by rememberSaveable { mutableStateOf(ProDial.ISO) }
    val current = dials.firstOrNull { it.dial == selected } ?: dials.firstOrNull() ?: return
    val settings = pro.settings
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        dev.opencamera.app.PerfTrace.endAfterNextFrame(view, dev.opencamera.app.PerfTrace.OPEN_PRO)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(s.radiusL))
            .background(Color.Black.copy(alpha = if (s.glass) .5f else .62f))
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceEvenly) {
            dials.forEach { d ->
                val on = d.dial == current.dial
                val name = stringResource(dialName(d.dial))
                Column(
                    Modifier
                        .heightIn(min = 48.dp)
                        .widthIn(min = 48.dp)
                        .clip(RoundedCornerShape(s.radiusS))
                        .then(if (on && !s.glass) Modifier.background(s.accentBrush) else Modifier)
                        .selectable(
                            on, role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { selected = d.dial }
                        .uprightGlyph(LocalGlyphRotation.current)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val color = when {
                        on && s.glass -> s.accent
                        on -> Color(0xFF111111)
                        else -> s.fg
                    }
                    BasicText(name, style = TextStyle(color = if (on) color else s.muted, fontSize = 10.sp, fontWeight = FontWeight.Medium))
                    BasicText(d.summary(settings), style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
        val evLocked = current.dial == ProDial.EV && settings.manualExposure
        Dial(
            model = current,
            index = current.index(settings),
            enabled = !evLocked,
            description = stringResource(dialName(current.dial)),
        ) { i -> actions.proSettings(current.apply(settings, i)) }
        ProToggles(pro, actions)
    }
}

private fun dialName(d: ProDial) = when (d) {
    ProDial.ISO -> R.string.pro_iso
    ProDial.SHUTTER -> R.string.pro_shutter
    ProDial.EV -> R.string.pro_ev
    ProDial.WB -> R.string.pro_wb
    ProDial.FOCUS -> R.string.pro_focus
    ProDial.METER -> R.string.pro_meter
}

/** Horizontal ruler: drag to step, one haptic tick per stop; TalkBack sets the stop directly. */
@Composable
private fun Dial(model: DialModel, index: Int, enabled: Boolean, description: String, onChange: (Int) -> Unit) {
    val s = skin
    val view = LocalView.current
    val density = LocalDensity.current
    val stepPx = with(density) { 14.dp.toPx() } * if (model.count <= 12) 3.2f else 1f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val indexNow by rememberUpdatedState(index)
    val onChangeNow by rememberUpdatedState(onChange)
    val modelNow by rememberUpdatedState(model)
    var accumulated by remember { mutableFloatStateOf(0f) }

    val position by animateFloatAsState(index.toFloat(), s.fast(), label = "dial")
    val label = model.label(index)
    val textPx = with(density) { 11.sp.toPx() }
    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .testTag("pro_dial")
            .semantics {
                contentDescription = description
                stateDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..(model.count - 1).toFloat(), model.count - 2)
                if (enabled) setProgress { v ->
                    val i = v.roundToInt().coerceIn(0, modelNow.count - 1)
                    if (i != indexNow) onChangeNow(i)
                    true
                }
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(model.dial) {
                    detectHorizontalDragGestures(onDragStart = { accumulated = 0f }) { change, amount ->
                        change.consume()
                        accumulated += if (rtl) amount else -amount
                        val steps = (accumulated / stepPx).toInt()
                        if (steps != 0) {
                            accumulated -= steps * stepPx
                            val next = (indexNow + steps).coerceIn(0, modelNow.count - 1)
                            if (next != indexNow) {
                                Haptics.tick(view)
                                onChangeNow(next)
                            }
                        }
                    }
                }
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.width / 2
            val sign = if (rtl) -1f else 1f
            val visible = (size.width / stepPx).toInt() / 2 + 2
            val first = (position.toInt() - visible).coerceAtLeast(0)
            val last = (position.toInt() + visible).coerceAtMost(model.count - 1)
            val labelEvery = when {
                model.count <= 12 -> 1
                model.count <= 40 -> 3
                else -> 10
            }
            paint.textSize = textPx
            for (i in first..last) {
                val x = mid + sign * (i - position) * stepPx
                if (x < 0 || x > size.width) continue
                val fade = (1f - abs(x - mid) / mid).coerceIn(0f, 1f)
                val major = i % labelEvery == 0
                drawLine(
                    s.fg.copy(alpha = fade * if (major) .9f else .4f),
                    Offset(x, 8.dp.toPx()), Offset(x, (if (major) 22 else 16).dp.toPx()),
                    strokeWidth = if (major) 2f else 1.2f,
                )
                if (major) {
                    paint.color = (if (i == index) s.accentText else s.muted).copy(alpha = fade).toArgbCompat()
                    drawIntoCanvas { it.nativeCanvas.drawText(model.label(i), x, 44.dp.toPx(), paint) }
                }
            }
            drawLine(s.accent, Offset(mid, 2.dp.toPx()), Offset(mid, 26.dp.toPx()), strokeWidth = 5f)
        }
        if (!enabled) {
            BasicText(
                stringResource(R.string.ui_pro_ev_locked), Modifier.align(Alignment.BottomCenter),
                style = TextStyle(color = s.muted, fontSize = 11.sp, textAlign = TextAlign.Center),
            )
        }
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(),
)

@Composable
private fun ProToggles(pro: ProUiState, actions: CameraUiActions) {
    val s = pro.settings
    val view = LocalView.current
    data class Item(val label: Int, val on: Boolean?, val action: () -> Unit)
    val items = buildList {
        if (!s.manualExposure) add(Item(R.string.pro_ae_lock, s.aeLock) { actions.proSettings(s.copy(aeLock = !s.aeLock)) })
        if (s.focusDiopters == null) add(Item(R.string.pro_af_lock, pro.afLocked) { actions.proAfLock(!pro.afLocked) })
        add(Item(R.string.pro_peaking, s.peaking) { actions.proSettings(s.copy(peaking = !s.peaking)) })
        add(Item(R.string.pro_zebra, s.zebra) { actions.proSettings(s.copy(zebra = !s.zebra)) })
        add(Item(R.string.pro_histogram, s.histogram) { actions.proSettings(s.copy(histogram = !s.histogram)) })
        add(Item(R.string.pro_reset, null) { actions.proReset() })
    }
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pro.formatLabel?.let { format ->
            item { Pill(format, null) { Haptics.segment(view); actions.proFormat() } }
        }
        items(items) { item ->
            Pill(stringResource(item.label), item.on) { Haptics.segment(view); item.action() }
        }
    }
}

/** Small toggle chip used by Pro and the sheets; [on] null = plain action. */
@Composable
fun Pill(text: String, on: Boolean?, onClick: () -> Unit) {
    val s = skin
    val active = on == true
    val stateText = when (on) {
        true -> stringResource(R.string.pro_state_on)
        false -> stringResource(R.string.pro_state_off)
        null -> null
    }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .semantics { if (stateText != null) stateDescription = stateText }
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (active) s.accentBrush else Brush.linearGradient(listOf(s.chip, s.chip))
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            BasicText(
                text,
                style = TextStyle(
                    color = if (active) Color(0xFF111111) else s.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}
