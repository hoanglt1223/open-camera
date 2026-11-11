/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Line glyphs drawn for the skins (24-unit grid, round strokes); no third-party icon set. */
object Glyphs {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun glyph(name: String, vararg paths: String, autoMirror: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply {
            paths.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val flash = glyph("flash", "M13 2 4 14h7l-1 8 9-12h-7z")
    val flashOff = glyph("flashOff", "M13 2 4 14h7l-1 8 9-12h-7z", "M3 3l18 18")
    val torch = glyph("torch", "M8 2h8v5l-2 3v12h-4V10L8 7z", "M12 13v2")
    val timer = glyph("timer", circle(12f, 13f, 8f), "M12 9v4l2 2M9 2h6")
    val ratio = glyph("ratio", "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z")
    val grid = glyph("grid", "M4 9h16M4 15h16M9 4v16M15 4v16")
    val looks = glyph("looks", circle(9f, 10f, 5f), circle(15f, 10f, 5f), circle(12f, 15f, 5f))
    val settings = glyph(
        "settings", circle(12f, 12f, 3f),
        "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1",
    )
    val flip = glyph("flip", "M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3", "M18 3v4h-4M6 21v-4h4", autoMirror = true)
    val live = glyph("live", circle(12f, 12f, 3f), circle(12f, 12f, 7f))
    val liveOff = glyph("liveOff", circle(12f, 12f, 3f), circle(12f, 12f, 7f), "M4 4l16 16")
    val close = glyph("close", "M6 6l12 12M18 6 6 18")
    val pause = glyph("pause", "M8 5v14M16 5v14")
    val play = glyph("play", "M7 4v16l13-8z")
    val frame = glyph("frame", "M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M3 17h18")
    val hdr = glyph("hdr", "M3 8v8M3 12h4M7 8v8M10 8v8h2a3 3 0 0 0 3-3v-2a3 3 0 0 0-3-3zM18 16V8h2a2 2 0 0 1 0 4h-2l3 4")
    val night = glyph("night", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val star = glyph("star", "M12 3l2.6 5.6 6.1 0.7-4.5 4.2 1.2 6L12 16.6 6.6 19.5l1.2-6L3.3 9.3l6.1-0.7z")
    val image = glyph("image", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M4 16l5-5 4 4 2-2 5 5")
    val lock = glyph("lock", "M7 11h10v9H7z", "M9 11V8a3 3 0 0 1 6 0v3")
    val scan = glyph("scan", "M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4", "M7 12h10")
    val video = glyph("video", "M4 7h11v10H4z", "M15 10l5-3v10l-5-3")
    val moon = glyph("moon", "M15 3a9 9 0 1 0 6 13A7 7 0 0 1 15 3z")
    val palette = glyph("palette", circle(12f, 12f, 9f), circle(8.5f, 10f, 1f), circle(12f, 7.5f, 1f), circle(15.5f, 10f, 1f))
    val check = glyph("check", "M5 12l5 5 9-10")
}

@Composable
fun Glyph(vector: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
