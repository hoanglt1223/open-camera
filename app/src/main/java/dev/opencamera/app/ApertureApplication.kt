/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app

import android.annotation.SuppressLint
import android.app.Application
import androidx.camera.camera2.internal.CameraCompatibilityFilter
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import dev.opencamera.app.repositories.CameraRepository
import dev.opencamera.app.repositories.DeviceProfileRepository
import dev.opencamera.app.repositories.MediaRepository
import dev.opencamera.app.repositories.OverlaysRepository
import dev.opencamera.app.repositories.PreferencesRepository
import dev.opencamera.vendorbridge.VendorBridge

class ApertureApplication : Application() {
    private val coroutineScope = MainScope()

    val cameraRepository by lazy { CameraRepository(this, coroutineScope, overlaysRepository) }
    val mediaRepository by lazy { MediaRepository(this) }
    val overlaysRepository by lazy { OverlaysRepository(this) }
    val preferencesRepository by lazy { PreferencesRepository(this, coroutineScope) }
    val deviceProfileRepository by lazy { DeviceProfileRepository(this) }
    val vendorBridge by lazy { VendorBridge(PreferenceManager.getDefaultSharedPreferences(this)) }

    @SuppressLint("RestrictedApi")
    override fun onCreate() {
        super.onCreate()

        // Observe dynamic colors changes
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Set backward compatible camera ids
        CameraCompatibilityFilter.setBackwardCompatibleCameraIds(
            overlaysRepository.backwardCompatibleCameraIds.asList()
        )

        // Generate and cache the device profile off the main thread on first launch
        coroutineScope.launch { deviceProfileRepository.profile() }
    }
}
