/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.TraceSectionMetric.Mode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
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
 * One test per acceptance row of the smoothness plan. Compilation is left to scripts/bench/run.sh
 * (speed-profile after dexopt), so every test uses [CompilationMode.Ignore].
 * Trace section names come from dev.opencamera.app.PerfTrace.
 */
@OptIn(ExperimentalMetricApi::class, ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class CameraBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val frames = FrameTimingMetric()
    private val longestMainFrame = TraceSectionMetric("Choreographer#doFrame%", Mode.Max, "mainFrame")
    private val binds = TraceSectionMetric("OcBind", Mode.Count)
    private fun section(name: String) = listOf(
        TraceSectionMetric(name, Mode.Average),
        TraceSectionMetric(name, Mode.Max),
    )

    private fun run(
        metrics: List<Metric>,
        startupMode: StartupMode? = null,
        setup: MacrobenchmarkScope.() -> Unit = { startCamera() },
        measure: MacrobenchmarkScope.() -> Unit,
    ) = rule.measureRepeated(
        packageName = Camera.PACKAGE,
        metrics = metrics,
        compilationMode = CompilationMode.Ignore(),
        startupMode = startupMode,
        iterations = Camera.iterations,
        setupBlock = setup,
        measureBlock = measure,
    )

    /** Cold start; TTFD is the first live preview frame (reportFullyDrawn). */
    @Test
    fun coldStart() = run(
        listOf(StartupTimingMetric(), binds),
        startupMode = StartupMode.COLD,
        setup = { pressHome() },
    ) {
        startActivityAndWait(Camera.launchIntent())
        find("shutter", 10_000)
        Thread.sleep(Camera.PREVIEW_SETTLE_MS)
    }

    /**
     * Not a timing test: cold start, Settings, editor and both sliders with StrictMode recording
     * (the app's DebugDiagnostics, enabled by the launch extra). Fails on any main-thread disk,
     * network or slow-call violation that passes through app code.
     */
    @Test
    fun strictModeClean() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        )
        device.executeShellCommand("logcat -c")
        rule.measureRepeated(
            packageName = Camera.PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Ignore(),
            startupMode = StartupMode.COLD,
            iterations = 1,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(Camera.launchIntent().putExtra(STRICT_EXTRA, true))
            find("shutter", 10_000)
            Thread.sleep(Camera.PREVIEW_SETTLE_MS)
            tap("settings")
            device.wait(Until.gone(By.res("shutter")), 3000)
            Thread.sleep(800)
            device.pressBack()
            find("shutter")
            selectMode("PRO")
            dragDial("pro_dial", 4)
            selectMode("PHOTO")
            startActivityAndWait(editorIntent().putExtra(STRICT_EXTRA, true))
            dragDial("editor_slider", 4)
            Thread.sleep(800)
        }
        val log = device.executeShellCommand("logcat -d -s OcStrictMode:E")
        val violations = log.lines().filter { "VIOLATION" in it }
        org.junit.Assert.assertTrue("StrictMode violations in app code:\n" + log, violations.isEmpty())
    }

    /** Photo -> Portrait -> Night -> Photo -> Pro -> Photo, twice: should keep the session (0 binds). */
    @Test
    fun modeSwitchSameSession() = run(listOf(frames, longestMainFrame, binds) + section("OcOpenPro")) {
        repeat(2) { for (m in Camera.SAME_SESSION_MODES) selectMode(m) }
    }

    /** Photo <-> Video, twice; each switch rebinds today. */
    @Test
    fun photoVideo() = run(listOf(frames, longestMainFrame, binds) + section("OcModeTapToPreview")) {
        repeat(2) {
            selectMode("VIDEO", Camera.PREVIEW_SETTLE_MS)
            selectMode("PHOTO", Camera.PREVIEW_SETTLE_MS)
        }
    }

    @Test
    fun openSettings() = run(listOf(frames, longestMainFrame) + section("OcOpenSettings")) {
        repeat(2) {
            tap("settings")
            device.wait(Until.gone(By.res("shutter")), 3000)
            Thread.sleep(800)
            device.pressBack()
            find("shutter")
            Thread.sleep(Camera.PREVIEW_SETTLE_MS)
        }
    }

    @Test
    fun openLooks() = run(listOf(frames, longestMainFrame) + section("OcOpenLooks")) {
        repeat(3) {
            tap("looks")
            find("looks_strip")
            Thread.sleep(700)
            tap("looks")
            Thread.sleep(700)
        }
    }

    @Test
    fun openPro() = run(listOf(frames, longestMainFrame, binds) + section("OcOpenPro")) {
        repeat(3) {
            selectMode("PRO")
            find("pro_dial")
            selectMode("PHOTO")
        }
    }

    /** Gallery is another app (system review intent): measured up to CameraActivity.onPause. */
    @Test
    fun openGallery() = run(listOf(frames, longestMainFrame) + section("OcOpenGallery")) {
        tap("gallery")
        device.wait(Until.gone(By.res("shutter")), 5000)
        Thread.sleep(1500)
        device.pressBack()
        find("shutter")
        Thread.sleep(Camera.PREVIEW_SETTLE_MS)
    }

    /** Editor opened on the newest photo (EDIT intent, as from the gallery's "Edit with"). */
    @Test
    fun openEditor() = run(
        listOf(StartupTimingMetric(), frames, longestMainFrame),
        startupMode = StartupMode.WARM,
        setup = { startCamera() },
    ) {
        startActivityAndWait(editorIntent())
        find("editor_slider", 10_000)
        Thread.sleep(800)
    }

    @Test
    fun proDialDrag() = run(
        listOf(frames, longestMainFrame, binds),
        setup = { startCamera(); selectMode("PRO"); find("pro_dial") },
    ) {
        dragDial("pro_dial", 8)
    }

    @Test
    fun editorSliderDrag() = run(
        listOf(frames, longestMainFrame),
        setup = {
            startActivityAndWait(editorIntent())
            find("editor_slider", 10_000)
            Thread.sleep(1500)
        },
    ) {
        dragDial("editor_slider", 8)
    }

    /** 0.6x <-> 1x; on gauguin the ultra-wide is a separate camera, so each tap rebinds. */
    @Test
    fun lensSwitch() = run(listOf(frames, longestMainFrame, binds) + section("OcLensTapToPreview")) {
        repeat(2) {
            tap("lens_0.6")
            Thread.sleep(Camera.PREVIEW_SETTLE_MS)
            tap("lens_1")
            Thread.sleep(Camera.PREVIEW_SETTLE_MS)
        }
    }

    /** Photo mode: shutter tap until the shutter accepts the next tap. */
    @Test
    fun shutterToReady() = run(listOf(frames, longestMainFrame) + section("OcShutterToReady")) {
        repeat(3) {
            tap("shutter")
            Thread.sleep(2500)
        }
    }

    private companion object {
        /** dev.opencamera.app.DebugDiagnostics.EXTRA_STRICT_CHECK */
        const val STRICT_EXTRA = "dev.opencamera.extra.STRICT_CHECK"
    }
}
