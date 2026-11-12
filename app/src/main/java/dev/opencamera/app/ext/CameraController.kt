/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ext

import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.core.ImageCapture
import androidx.camera.core.TorchState
import androidx.camera.view.CameraController
import dev.opencamera.app.models.FlashMode

var CameraController.flashMode: FlashMode
    get() = when (torchState.value) {
        TorchState.ON -> FlashMode.TORCH
        else -> when (imageCaptureFlashMode) {
            ImageCapture.FLASH_MODE_AUTO -> FlashMode.AUTO
            ImageCapture.FLASH_MODE_ON -> FlashMode.ON
            ImageCapture.FLASH_MODE_OFF -> FlashMode.OFF
            ImageCapture.FLASH_MODE_SCREEN -> FlashMode.SCREEN
            else -> throw Exception("Invalid flash mode")
        }
    }
    set(value) {
        enableTorch(value == FlashMode.TORCH)

        val mode = when (value) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.TORCH -> ImageCapture.FLASH_MODE_OFF
            FlashMode.SCREEN -> ImageCapture.FLASH_MODE_SCREEN
        }
        try {
            imageCaptureFlashMode = mode
        } catch (e: IllegalArgumentException) {
            // Screen flash is rejected once the controller already points at a back camera (a
            // flip racing the flash flow); the flash flow re-emits for the new camera.
            Log.w("CameraControllerExt", "Flash mode $value rejected, using off", e)
            imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
        }
    }

val CameraController.camera2CameraControl: Camera2CameraControl?
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    get() = cameraControl?.let { Camera2CameraControl.from(it) }
