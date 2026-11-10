/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ext

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel

val AndroidViewModel.applicationContext: Context
    get() = getApplication<Application>().applicationContext
