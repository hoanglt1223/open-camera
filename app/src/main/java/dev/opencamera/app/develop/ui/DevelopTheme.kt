/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui

/**
 * Colour and shape tokens of the editor. Camera UI skins can supply their own by setting
 * [DevelopTheme.current] before the editor opens; every editor view reads only these tokens.
 */
interface DevelopTheme {
    val background: Int
    val surface: Int
    val onSurface: Int
    val onSurfaceMuted: Int
    val accent: Int
    val onAccent: Int
    val warning: Int
    val cornerRadiusDp: Float

    companion object {
        @Volatile
        var current: DevelopTheme = Default
    }

    object Default : DevelopTheme {
        override val background = 0xff0e0e10.toInt()
        override val surface = 0xff1c1c20.toInt()
        override val onSurface = 0xffeeeeee.toInt()
        override val onSurfaceMuted = 0xff9a9aa2.toInt()
        override val accent = 0xffffc940.toInt()
        override val onAccent = 0xff1a1a1a.toInt()
        override val warning = 0xffff5050.toInt()
        override val cornerRadiusDp = 12f
    }
}
