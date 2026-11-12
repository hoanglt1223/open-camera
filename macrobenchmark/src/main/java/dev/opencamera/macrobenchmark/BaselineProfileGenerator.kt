/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.opencamera.macrobenchmark.Camera.dragDial
import dev.opencamera.macrobenchmark.Camera.editorIntent
import dev.opencamera.macrobenchmark.Camera.find
import dev.opencamera.macrobenchmark.Camera.selectMode
import dev.opencamera.macrobenchmark.Camera.startCamera
import dev.opencamera.macrobenchmark.Camera.tap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the rules of app/src/main/baseline-prof.txt from the benchmark scenarios.
 * scripts/bench/run.sh --profile pulls the output and merges it into the committed file.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = Camera.PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startCamera()
        for (m in Camera.SAME_SESSION_MODES) selectMode(m)
        selectMode("PRO"); dragDial("pro_dial", 4); selectMode("PHOTO")
        selectMode("VIDEO", Camera.PREVIEW_SETTLE_MS); selectMode("PHOTO", Camera.PREVIEW_SETTLE_MS)
        tap("lens_0.6"); Thread.sleep(Camera.PREVIEW_SETTLE_MS); tap("lens_1"); Thread.sleep(Camera.PREVIEW_SETTLE_MS)
        tap("looks"); find("looks_strip"); Thread.sleep(500); tap("looks")
        // A dark-room HDR burst keeps the shutter busy for several seconds; a settings tap during
        // it is ignored, and the back press would then leave the app.
        tap("shutter"); Thread.sleep(6000)
        tap("settings")
        if (device.wait(Until.gone(By.res("shutter")), 3000) == true) { Thread.sleep(800); device.pressBack() }
        find("shutter")
        startActivityAndWait(editorIntent())
        find("editor_slider", 10_000)
        dragDial("editor_slider", 4)
        device.pressBack()
    }
}
