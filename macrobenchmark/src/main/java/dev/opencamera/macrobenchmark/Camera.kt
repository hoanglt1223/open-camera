/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.macrobenchmark

import android.content.Intent
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Target package and UI driving shared by the benchmarks and the baseline profile generator. */
object Camera {
    val PACKAGE: String =
        InstrumentationRegistry.getArguments().getString("targetPackage") ?: "dev.opencamera.app.bench"

    const val ACTIVITY = "dev.opencamera.app.CameraActivity"
    const val EDITOR = "dev.opencamera.app.develop.DevelopActivity"

    /** Time for a live preview after a launch or a rebind; the app has no "ready" signal for UiAutomator. */
    const val PREVIEW_SETTLE_MS = 1500L

    val iterations: Int =
        InstrumentationRegistry.getArguments().getString("iterations")?.toIntOrNull() ?: 5

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN).setClassName(PACKAGE, ACTIVITY)

    private fun res(id: String): BySelector = By.res(id)

    fun MacrobenchmarkScope.find(id: String, timeoutMs: Long = 5000): UiObject2 =
        device.wait(Until.findObject(res(id)), timeoutMs)
            ?: error("UI element '$id' not found in ${device.currentPackageName}")

    fun MacrobenchmarkScope.tap(id: String) = find(id).click()

    fun MacrobenchmarkScope.startCamera() {
        startActivityAndWait(launchIntent())
        find("shutter", 10_000)
        Thread.sleep(PREVIEW_SETTLE_MS)
        selectMode("PHOTO")
        // Lens 1x and closed looks are the state every scenario starts from.
        device.findObject(res("lens_1"))?.takeIf { !it.isSelected }?.click()
        device.findObject(res("looks_strip"))?.let { tap("looks"); Thread.sleep(600) }
    }

    /** Each step is visible from the previous one (the carousel only shows neighbours). */
    val SAME_SESSION_MODES = listOf("PORTRAIT", "NIGHT", "PHOTO", "PRO", "PHOTO")

    /** Taps a mode tab unless it is already selected; returns whether a tap happened. */
    fun MacrobenchmarkScope.selectMode(mode: String, settleMs: Long = 900): Boolean {
        val tab = find("mode_$mode")
        if (tab.isSelected) return false
        tab.click()
        Thread.sleep(settleMs)
        return true
    }

    fun MacrobenchmarkScope.dragDial(id: String, swipes: Int) {
        val dial = find(id)
        repeat(swipes) { i ->
            // Alternating directions keep the value inside its range.
            dial.swipe(if (i % 2 == 0) Direction.LEFT else Direction.RIGHT, 0.6f, 1200)
            Thread.sleep(150)
        }
    }

    /** Newest photo in MediaStore, read through the shell so the test needs no storage permission. */
    fun MacrobenchmarkScope.latestImage(): Uri {
        // UiAutomation runs the command without a shell, so no quoted sort clause: take the max id.
        val out = device.executeShellCommand(
            "content query --uri content://media/external/images/media --projection _id"
        )
        val id = Regex("_id=(\\d+)").findAll(out).maxOfOrNull { it.groupValues[1].toLong() }
            ?: error("No photo in MediaStore; take one first")
        return Uri.parse("content://media/external/images/media/$id")
    }

    fun MacrobenchmarkScope.editorIntent(): Intent =
        Intent(Intent.ACTION_EDIT).setClassName(PACKAGE, EDITOR)
            .setDataAndType(latestImage(), "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
