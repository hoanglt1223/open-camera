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
    private val hintsWanted: () -> Boolean = { false },
    /** Subject analysis for composition hints: suggestion (null = no subject) and subject top. */
    private val onComposition: (Composition.Suggestion?, Float?) -> Unit = { _, _ -> },
) {
    private val context = viewFinder.context
    val settings = AiSettings(context)
    private val runtime = MlRuntime.get(context)
    private val segmenter = Segmenter(runtime)
    private val bokeh = BokehProcessor(segmenter)
    private val faces = FaceMesh(runtime)
    private val scenes = SceneDetector(context, runtime, faces)
    private val saliency = SaliencyDetector(runtime, maxModelMs = 400)

    private val stabilizer = dev.opencamera.pipeline.ai.MaskStabilizer()
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
            val compositionOn = photo && (settings.composition || hintsWanted()) && compositionOk
            val wantComposition = compositionOn && now - lastComposition >= COMPOSITION_INTERVAL_MS
            if (!wantBokeh) {
                bokehView.isVisible = false
                stabilizer.reset()
            }
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
            if (wantBokeh && !stabilizer.visible) {
                bokehView.isVisible = false
            }
            result.bokeh?.let {
                bokehView.setImageBitmap(it)
                bokehView.isVisible = settings.portrait && isPhotoMode()
                logFps()
            }
            result.scene?.let(::onSceneDetected)
            if (wantComposition) {
                overlay.suggestion = result.composition.takeIf { settings.composition }
                onComposition(result.composition, result.subjectTop)
            } else if (!compositionOn) {
                onComposition(null, null)
            }
            val spent = SystemClock.uptimeMillis() - now
            delay(if (wantBokeh) max(0L, LIVE_FRAME_MS - spent) else IDLE_MS)
        }
    }

    private class Analysis(
        val bokeh: Bitmap?,
        val scene: Scene?,
        val composition: Composition.Suggestion?,
        val subjectTop: Float?,
    )

    private fun analyze(frame: Bitmap, wantBokeh: Boolean, wantScene: Boolean, wantComposition: Boolean): Analysis {
        val k = LIVE_LONG_SIDE.toFloat() / max(frame.width, frame.height)
        val small = ImageTensors.scaled(frame, (frame.width * k).roundToInt(), (frame.height * k).roundToInt())
        if (small !== frame) frame.recycle()
        try {
            // Only the blurred background is drawn over the live preview (subject transparent),
            // and only once a person is steadily detected; no subject means no blur at all.
            val bokehBitmap = if (wantBokeh) guard("live portrait", { liveBokehOk = false }) {
                // The selfie segmenter finds "people" in clutter; only blur once a face confirms one.
                val p = if (personConfirmed(small)) bokeh.prepare(small, 0, LIVE_LONG_SIDE, preferMulticlass = false) else null
                try {
                    stabilizer.update(p?.mask)?.let { m ->
                        p?.let { bokeh.renderBackgroundLayer(it, m, BokehProcessor.Params(settings.portraitAperture)) }
                    }
                } finally {
                    p?.release()
                }
            } else null
            val scene = if (wantScene) guard("scene", { sceneOk = false }) { scenes.detect(small).scene } else null
            var top: Float? = null
            val comp = if (wantComposition) guard("composition", { compositionOk = false }) {
                val mask = saliency.saliency(small).first
                top = subjectTop(mask)
                Composition.suggest(mask)
            } else null
            return Analysis(bokehBitmap, scene, comp, top)
        } finally {
            small.recycle()
        }
    }

    private var faceSeenAt = 0L
    private var faceCheckedAt = 0L

    /** A face seen within [FACE_HOLD_MS]; the detector runs at most every [FACE_CHECK_MS]. */
    private fun personConfirmed(frame: Bitmap): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - faceCheckedAt >= FACE_CHECK_MS) {
            faceCheckedAt = now
            val found = runCatching { faces.detectBoxes(frame, 1) }.getOrNull()
                ?.any { it.second >= FACE_MIN_SCORE } == true
            if (found) faceSeenAt = now
        }
        return now - faceSeenAt <= FACE_HOLD_MS
    }

    /** Normalized first row where the salient subject covers a few percent of the width. */
    private fun subjectTop(mask: dev.opencamera.ml.tasks.Mask): Float? {
        for (y in 0 until mask.height) {
            var n = 0
            for (x in 0 until mask.width) if (mask.data[y * mask.width + x] > 0.5f) n++
            if (n >= mask.width / 20 + 1) return y.toFloat() / mask.height
        }
        return null
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
        const val FACE_CHECK_MS = 300L
        const val FACE_HOLD_MS = 1200L
        const val FACE_MIN_SCORE = 0.6f
        const val LIVE_FRAME_MS = 66L
        const val IDLE_MS = 250L
        const val SCENE_INTERVAL_MS = 1000L
        const val COMPOSITION_INTERVAL_MS = 1500L
    }
}
