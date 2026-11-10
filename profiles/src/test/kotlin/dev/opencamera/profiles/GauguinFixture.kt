/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

import dev.opencamera.profiles.Capability.BACKWARD_COMPATIBLE
import dev.opencamera.profiles.Capability.LOGICAL_MULTI_CAMERA
import dev.opencamera.profiles.Capability.RAW

/** Values from `dumpsys media.camera` on Xiaomi gauguin (LineageOS 24). */
object GauguinFixture {
    private val caps = setOf(BACKWARD_COMPATIBLE, RAW)

    val build = BuildInfo("Xiaomi", "gauguin", "M2007J17G", "Xiaomi/gauguin/gauguin:17/test", 37)

    val specs = listOf(
        CameraSpec("0", Facing.BACK, listOf(5.43f), 7.3984f, 5.5552f, 4624, 3472, 10f, caps),
        CameraSpec("1", Facing.FRONT, listOf(3.26f), 4.656f, 3.496f, 2328, 1748, 0f, caps),
        CameraSpec("2", Facing.BACK, listOf(1.93f), 2.8f, 2.1f, 1600, 1200, 0f, caps),
        CameraSpec("3", Facing.BACK, listOf(1.65f), 3.65568f, 2.74176f, 3264, 2448, 0f, caps),
        CameraSpec("4", Facing.BACK, listOf(1.933f), 2.8f, 2.1f, 1600, 1200, 0f, caps),
        CameraSpec(
            "5", Facing.BACK, listOf(5.43f), 7.3984f, 5.5552f, 4624, 3472, 10f,
            caps + LOGICAL_MULTI_CAMERA, physicalIds = listOf("0", "4"),
        ),
        CameraSpec("6", Facing.BACK, listOf(1.65f), 3.65568f, 2.74176f, 3264, 2448, 0f, caps),
        CameraSpec("7", Facing.FRONT, listOf(3.26f), 4.656f, 3.496f, 2328, 1748, 0f, caps),
    )
}