/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.models

enum class CameraFacing(
    val cameraType: CameraType,
) {
    UNKNOWN(
        CameraType.INTERNAL,
    ),
    FRONT(
        CameraType.INTERNAL,
    ),
    BACK(
        CameraType.INTERNAL,
    ),
    EXTERNAL(
        CameraType.EXTERNAL,
    ),
}
