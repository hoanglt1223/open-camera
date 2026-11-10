/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app

import dev.opencamera.app.models.CameraMode

class QrScannerActivity : CameraActivity() {
    override fun overrideInitialCameraMode() = CameraMode.QR
}
