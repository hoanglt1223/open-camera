/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import dev.opencamera.app.R
import dev.opencamera.app.models.CameraMode
import dev.opencamera.app.modes.CreativeMode
import dev.opencamera.app.ui.skin.Skins
import dev.opencamera.app.video.VideoMode
import dev.opencamera.capture.MultiFrameSetting

fun controlName(c: Control) = when (c) {
    Control.ASPECT -> R.string.ui_row_aspect
    Control.EFFECT -> R.string.ui_row_effect
    Control.LOOK -> R.string.look_button
    Control.VIDEO_QUALITY -> R.string.ui_row_video_quality
    Control.VIDEO_FPS -> R.string.ui_row_video_fps
    Control.VIDEO_DYNAMIC_RANGE -> R.string.ui_row_video_dynamic_range
    Control.MIC -> R.string.ui_mic
    Control.TIMER -> R.string.ui_timer
    Control.PORTRAIT -> R.string.ui_mode_portrait
    Control.MULTI_FRAME -> R.string.ui_hdr
    Control.CREATIVE -> R.string.ui_row_creative
    Control.MOTION_PHOTO -> R.string.ui_motion_photo
    Control.PRE_SHOT -> R.string.ui_row_pre_shot
    Control.GRID -> R.string.ui_row_grid
}

@Composable
fun BoxScope.Sheets(state: CameraUiState, actions: CameraUiActions, ui: OverlayUiState) {
    CountRecompositions("Sheets")
    val s = skin
    val open = ui.sheet != Sheet.NONE
    val dim = animateFloatAsState(if (open) .35f else 0f, s.medium(), label = "dim")
    // The fade is read in the draw phase; composition only sees the scrim appear and disappear.
    val dimmed by remember { derivedStateOf { dim.value > 0f } }
    if (dimmed) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = dim.value }
                .background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = stringResource(R.string.ui_close)) {
                    ui.sheet = Sheet.NONE
                }
        )
    }
    // Keep the last sheet's content while it slides out.
    var shown by remember { mutableStateOf(Sheet.MORE) }
    if (open) shown = ui.sheet
    AnimatedVisibility(
        open,
        Modifier.align(Alignment.BottomCenter),
        enter = s.enter { it },
        exit = s.exit { it },
    ) {
        val bottomPad = with(LocalDensity.current) { state.insetBottom.toDp() }
        SheetSurface {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = bottomPad + 20.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clickable(remember { MutableInteractionSource() }, null, onClickLabel = stringResource(R.string.ui_close)) {
                            ui.sheet = Sheet.NONE
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(s.stroke))
                }
                when (shown) {
                    Sheet.MORE -> MoreSheet(state, actions) { ui.sheet = Sheet.NONE }
                    Sheet.SETTINGS -> SettingsSheet(state, actions) { ui.sheet = Sheet.NONE }
                    Sheet.NONE -> Unit
                }
            }
        }
    }
}

/**
 * Sheet background: a solid surface. Glass adds a fixed top-down sheen and a hairline edge so
 * it reads frosted without copying or blurring the preview.
 */
@Composable
private fun SheetSurface(content: @Composable () -> Unit) {
    val s = skin
    val shape = RoundedCornerShape(topStart = s.radiusL, topEnd = s.radiusL)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(s.sheet)
            .then(if (s.glass) Modifier.background(GlassSheen).border(1.dp, s.stroke, shape) else Modifier)
            .semantics { paneTitle = "sheet" },
    ) {
        content()
    }
}

private val GlassSheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .02f)))

@Composable
private fun MoreSheet(state: CameraUiState, actions: CameraUiActions, close: () -> Unit) {
    val s = skin
    val view = LocalView.current
    SheetTitle(stringResource(R.string.ui_more_title))
    data class Tile(val label: String, val glyph: androidx.compose.ui.graphics.vector.ImageVector, val on: Boolean, val action: () -> Unit)
    val tiles = buildList {
        state.moreModes.fastForEach { m ->
            add(Tile(stringResource(m.label), if (m == VideoMode.PANORAMA || m == VideoMode.DOCUMENT) Glyphs.frame else Glyphs.video, false) {
                actions.openMoreMode(m)
            })
        }
        CreativeMode.entries.filter { it != CreativeMode.OFF }.fastForEach { m ->
            add(Tile(stringResource(m.label), if (m == CreativeMode.MOON) Glyphs.moon else Glyphs.frame, state.creative == m) {
                actions.setCreative(if (state.creative == m) CreativeMode.OFF else m)
            })
        }
        add(Tile(stringResource(R.string.multi_frame_astro), Glyphs.star, state.multiFrame == MultiFrameSetting.ASTRO) {
            actions.setMultiFrame(if (state.multiFrame == MultiFrameSetting.ASTRO) MultiFrameSetting.HDR_AUTO else MultiFrameSetting.ASTRO)
        })
        add(Tile(stringResource(R.string.multi_frame_hdr_on), Glyphs.hdr, state.multiFrame == MultiFrameSetting.HDR_ON) {
            actions.setMultiFrame(if (state.multiFrame == MultiFrameSetting.HDR_ON) MultiFrameSetting.HDR_AUTO else MultiFrameSetting.HDR_ON)
        })
        add(Tile(stringResource(R.string.look_color_match), Glyphs.palette, false) { actions.colorMatch() })
        if (state.googleLens) add(Tile(stringResource(R.string.google_lens_button_description), Glyphs.scan, false) { actions.googleLens() })
        val next = Skins.other(s.id)
        add(Tile(stringResource(if (next.id == Skins.GLASS_ID) R.string.ui_skin_glass else R.string.ui_skin_origin), Glyphs.palette, false) {
            actions.setSkin(next.id)
        })
        add(Tile(stringResource(R.string.ui_all_settings), Glyphs.settings, false) { actions.allSettings() })
    }
    val shape = if (s.glass) RoundedCornerShape(14.dp) else CircleShape
    tiles.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            row.forEach { t ->
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 76.dp)
                        .semantics { if (t.on) stateDescription = view.context.getString(R.string.pro_state_on) }
                        .pressable { Haptics.segment(view); close(); t.action() }
                        .uprightGlyph(LocalGlyphRotation.current),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(48.dp).clip(shape).background(if (t.on) s.accentBrush else SolidColor(s.chip)),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(t.glyph, if (t.on) Color(0xFF111111) else s.fg) }
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        t.label,
                        style = TextStyle(color = s.fg, fontSize = 11.sp, textAlign = TextAlign.Center),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SettingsSheet(state: CameraUiState, actions: CameraUiActions, close: () -> Unit) {
    val s = skin
    val view = LocalView.current
    SheetTitle(stringResource(R.string.ui_settings))

    // Skin picker: instant, no camera rebind.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(R.string.ui_skin), Modifier.weight(1f), style = TextStyle(color = s.fg, fontSize = 15.sp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(s.chip).padding(2.dp).selectableGroup(),
        ) {
            Skins.ALL.forEach { t ->
                val on = t.id == s.id
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .selectable(on, role = Role.RadioButton, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            Haptics.segment(view)
                            actions.setSkin(t.id)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        stringResource(if (t.id == Skins.GLASS_ID) R.string.ui_skin_glass else R.string.ui_skin_origin),
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .then(if (on) Modifier.background(s.accentBrush) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        style = TextStyle(
                            color = if (on) Color(0xFF111111) else s.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
    Divider()

    val rows = when (state.cameraMode) {
        CameraMode.PHOTO -> listOf(
            Control.ASPECT, Control.EFFECT, Control.MULTI_FRAME, Control.CREATIVE, Control.PORTRAIT,
            Control.MOTION_PHOTO, Control.PRE_SHOT, Control.TIMER, Control.GRID,
        )
        CameraMode.VIDEO -> listOf(
            Control.VIDEO_QUALITY, Control.VIDEO_FPS, Control.VIDEO_DYNAMIC_RANGE, Control.MIC, Control.TIMER, Control.GRID,
        )
        CameraMode.QR -> listOf(Control.GRID)
    }
    if (state.cameraMode == CameraMode.PHOTO && state.imageQuality.isNotEmpty()) {
        ValueRow(stringResource(R.string.image_quality_title), state.imageQuality, true) {
            Haptics.segment(view)
            actions.cycleImageQuality()
        }
        Divider()
    }
    rows.forEach { c ->
        val ctl = state.controls[c] ?: return@forEach
        if (!ctl.visible) return@forEach
        ValueRow(stringResource(controlName(c)), ctl.label, ctl.enabled) {
            Haptics.segment(view)
            actions.control(c)
        }
        Divider()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable { close(); actions.allSettings() },
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            stringResource(R.string.ui_all_settings),
            style = TextStyle(color = s.accentText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}


@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(skin.stroke))

@Composable
private fun ValueRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics { contentDescription = label; stateDescription = value }
            .clickable(remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else .45f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = TextStyle(color = s.fg, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(value, style = TextStyle(color = s.muted, fontSize = 14.sp), maxLines = 1)
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val s = skin
    val knob by animateFloatAsState(if (on) 1f else 0f, s.bouncy(), label = "switch")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(on, remember { MutableInteractionSource() }, null, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, Modifier.weight(1f), style = TextStyle(color = s.fg, fontSize = 15.sp))
        Box(
            Modifier
                .size(46.dp, 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (on) s.switchOn else s.stroke)
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .graphicsLayer { translationX = knob * 18.dp.toPx() }
                    .size(22.dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}
