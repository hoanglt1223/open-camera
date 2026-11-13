/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.models

enum class TimerMode(val seconds: Int) {
    OFF(0),
    ON_3S(3),
    ON_10S(10),

    /**
     * Waits for a raised palm or everyone smiling with open eyes ([dev.opencamera.app.ai.SmartTimer]).
     * The preference stores [seconds], so this needs a value of its own.
     */
    SMART(-1),
}
