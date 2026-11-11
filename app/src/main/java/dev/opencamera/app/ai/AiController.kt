/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.opencamera.app.R
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.Composition
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.ml.tasks.SaliencyDetector
import dev.opencamera.ml.tasks.Scene
import dev.opencamera.ml.tasks.SceneDetector
import dev.opencamera.ml.tasks.Segmenter
import dev.opencamera.pipeline.ai.AiOps
import dev.opencamera.pipeline.ai.BokehProcessor
import dev.opencamera.pipeline.ai.NativeAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Viewfinder ML: live portrait preview, scene chip and composition hint. Works on
 * [PreviewView.getBitmap] snapshots, so it adds no camera stream and cannot affect binding.
 * Each feature turns itself off for the session (with one toast) after a failure.
 */
class AiController(
    private val owner: LifecycleOwner,
    private val viewFinder: PreviewView,
    private val bokehView: ImageView,
    private val overlay: CompositionOverlayView,
    private val sceneChip: TextView,
    private val isPhotoMode: () -> Boolean,
    private val onScenePreset: (Scene) -> Unit,
) {
    private val context = viewFinder.context
    val settings = AiSettings(context)
    private val runtime = MlRuntime.get(context)
    private val segmenter = Segmenter(runtime)
    private val bokeh = BokehProcessor(segmenter)
    private val faces = FaceMesh(runtime)
    private val scenes = SceneDetector(context, runtime, faces)
    private val saliency = SaliencyDetector(runtime, maxModelMs = 400)

    private var liveBokehOk = true
    private var sceneOk = true
    private var compositionOk = true

    private var shownScene = Scene.NONE
    private var candidateScene = Scene.NONE
    private var lastFpsLog = 0L
    private var frames = 0

    /** Steps queued with each saved photo. */
    fun captureOps(): AiOps? {
        if (!isPhotoMode()) return null
        val aperture = if (settings.portrait) settings.portraitAperture else null
        val ops = AiOps(portraitAperture = aperture, beauty = settings.beauty)
        return ops.takeUnless { it.isEmpty }
    }

    fun start() {
        sceneChip.setOnClickListener { shownScene.takeIf { it != Scene.NONE }?.let(onScenePreset) }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    loop()
                } finally {
                    bokehView.isVisible = false
                    overlay.suggestion = null
                }
            }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.loop() {
        var lastScene = 0L
        var lastComposition = 0L
        while (isActive) {
            val now = SystemClock.uptimeMillis()
            val photo = isPhotoMode()
            val wantBokeh = photo && settings.portrait && settings.livePortraitPreview && liveBokehOk && NativeAi.available
            val wantScene = photo && settings.sceneDetection && sceneOk && now - lastScene >= SCENE_INTERVAL_MS
            val wantComposition = photo && settings.composition && compositionOk && now - lastComposition >= COMPOSITION_INTERVAL_MS
            if (!wantBokeh) bokehView.isVisible = false
            if (!(photo && settings.composition && compositionOk)) overlay.suggestion = null
            if (!(photo && settings.sceneDetection && sceneOk)) showScene(Scene.NONE)
            if (!wantBokeh && !wantScene && !wantComposition) {
                delay(IDLE_MS)
                continue
            }
            val frame = runCatching { viewFinder.bitmap }.getOrNull()
            if (frame == null) {
                delay(IDLE_MS)
                continue
            }
            val result = withContext(Dispatchers.Default) {
                analyze(frame, wantBokeh, wantScene, wantComposition)
            }
            if (wantScene) lastScene = now
            if (wantComposition) lastComposition = now
            result.bokeh?.let {
                bokehView.setImageBitmap(it)
                bokehView.isVisible = settings.portrait
                logFps()
            }
            result.scene?.let(::onSceneDetected)
            if (wantComposition) overlay.suggestion = result.composition
            val spent = SystemClock.uptimeMillis() - now
            delay(if (wantBokeh) max(0L, LIVE_FRAME_MS - spent) else IDLE_MS)
        }
    }

    private class Analysis(val bokeh: Bitmap?, val scene: Scene?, val composition: Composition.Suggestion?)

    private fun analyze(frame: Bitmap, wantBokeh: Boolean, wantScene: Boolean, wantComposition: Boolean): Analysis {
        val k = LIVE_LONG_SIDE.toFloat() / max(frame.width, frame.height)
        val small = ImageTensors.scaled(frame, (frame.width * k).roundToInt(), (frame.height * k).roundToInt())
        if (small !== frame) frame.recycle()
        try {
            val bokehBitmap = if (wantBokeh) guard("live portrait", { liveBokehOk = false }) {
                bokeh.prepare(small, 0, LIVE_LONG_SIDE, preferMulticlass = false)?.let { p ->
                    try {
                        bokeh.renderPreview(p, BokehProcessor.Params(settings.portraitAperture))
                    } finally {
                        p.release()
                    }
                } ?: small.copy(Bitmap.Config.ARGB_8888, false)
            } else null
            val scene = if (wantScene) guard("scene", { sceneOk = false }) { scenes.detect(small).scene } else null
            val comp = if (wantComposition) guard("composition", { compositionOk = false }) {
                Composition.suggest(saliency.saliency(small).first)
            } else null
            return Analysis(bokehBitmap, scene, comp)
        } finally {
            small.recycle()
        }
    }

    private inline fun <T> guard(name: String, crossinline disable: () -> Unit, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
        Log.e(TAG, "$name failed, turning it off for this session", e)
        disable()
        viewFinder.post {
            Toast.makeText(context, context.getString(R.string.ai_feature_disabled, name), Toast.LENGTH_SHORT).show()
        }
        null
    }

    private fun onSceneDetected(scene: Scene) {
        // Two agreeing detections in a row before the chip changes, so it does not flicker.
        if (scene != candidateScene) {
            candidateScene = scene
            return
        }
        showScene(scene)
    }

    private fun showScene(scene: Scene) {
        if (scene == shownScene) return
        shownScene = scene
        sceneChip.isVisible = scene != Scene.NONE
        sceneChip.text = sceneLabel(scene)
    }

    private fun sceneLabel(scene: Scene) = context.getString(
        when (scene) {
            Scene.FOOD -> R.string.ai_scene_food
            Scene.SKY -> R.string.ai_scene_sky
            Scene.NIGHT -> R.string.ai_scene_night
            Scene.DOCUMENT -> R.string.ai_scene_document
            Scene.PORTRAIT -> R.string.ai_scene_portrait
            Scene.PLANT -> R.string.ai_scene_plant
            Scene.TEXT -> R.string.ai_scene_text
            Scene.NONE -> R.string.ai_scene_none
        }
    )

    private fun logFps() {
        frames++
        val now = SystemClock.uptimeMillis()
        if (lastFpsLog == 0L) lastFpsLog = now
        if (now - lastFpsLog >= 5000) {
            Log.i(TAG, "Live portrait %.1f fps, segmenter on %s".format(frames * 1000f / (now - lastFpsLog), runtime.accelerator(Segmenter.SELFIE)))
            frames = 0
            lastFpsLog = now
        }
    }

    private companion object {
        const val TAG = "AiController"
        const val LIVE_LONG_SIDE = 480
        const val LIVE_FRAME_MS = 66L
        const val IDLE_MS = 250L
        const val SCENE_INTERVAL_MS = 1000L
        const val COMPOSITION_INTERVAL_MS = 1500L
    }
}
