/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.camera.core.Preview
import androidx.camera.view.CameraController
import dev.opencamera.pipeline.ai.AiOps
import dev.opencamera.pipeline.looks.LookEffect
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.LookPreviewSurfaceProvider
import dev.opencamera.pipeline.looks.LookSurfaceProcessor
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/**
 * Owns the preview look effect and hands captured photos to the processing queue.
 * Must be used from the main thread.
 */
class LookController(
    context: Context,
    private val cameraController: CameraController,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val prefs = LookPreferences(app)
    val repository = LooksRepository.get(app)

    private val _lookId = MutableStateFlow(prefs.lookId)
    val lookId = _lookId.asStateFlow()

    private var processor: LookSurfaceProcessor? = null
    private var effectAttached = false
    private var applied: Pair<String, Float>? = null
    private var previewEffectBlocked = false

    /** CameraX rejects effects next to RAW capture; the look is then applied to saved photos only. */
    private var rawSession = false

    private var ownPreview: LookPreviewSurfaceProvider? = null

    /**
     * True while the viewfinder is drawn by our own renderer ([wrapPreview]): the look is live in
     * every session layout (RAW included) and switching looks never touches the session.
     */
    val ownsPreview get() = ownPreview?.enabled == true

    /** Preview rate measured by the look renderer over the last two seconds; 0 when unknown. */
    val previewFps get() = processor?.fps ?: 0f

    /**
     * Wraps the preview view's surface provider so frames pass through the look renderer.
     * [onFailure] runs on the main thread when the renderer cannot serve the view; later binds
     * then use the CameraX effect path (no live look next to RAW).
     */
    fun wrapPreview(context: Context, target: Preview.SurfaceProvider, onFailure: () -> Unit): Preview.SurfaceProvider {
        if (processor == null && !previewEffectBlocked) {
            processor = runCatching { LookSurfaceProcessor() }.onFailure { Log.w(TAG, "No GL for looks", it) }.getOrNull()
        }
        return LookPreviewSurfaceProvider(context, target, { processor }) {
            // The next refresh re-applies the look through the effect path.
            applied = null
            onFailure()
        }.also { ownPreview = it }
    }

    private var preloaded: Deferred<LookParams?>? = null
    private var preloadedKey: Pair<String, Float>? = null

    /**
     * Starts loading the saved look and setting up GL so the first camera bind can carry the
     * effect ([attachIfReady]) instead of rebinding once the look arrives.
     */
    fun preload() {
        val key = prefs.lookId to prefs.intensity
        if (preloadedKey == key && preloaded != null) return
        preloadedKey = key
        // GL is set up for Original too: the effect is attached as a pass-through from the first
        // bind, so picking a look later only swaps the LUT instead of rebinding the session.
        if (!previewEffectBlocked && processor == null) {
            processor = runCatching { LookSurfaceProcessor() }.onFailure { Log.w(TAG, "No GL for looks", it) }.getOrNull()
        }
        if (key.first == LookPreferences.NONE) {
            preloaded = null
            // Warm the look list (asset JSON) off the main thread for the picker.
            scope.launch(Dispatchers.IO) { runCatching { repository.descriptors } }
            return
        }
        preloaded = scope.async(Dispatchers.IO) {
            runCatching { repository.params(key.first, key.second) }
                .onFailure { Log.e(TAG, "Cannot load look ${key.first}", it) }
                .getOrNull()
        }
    }

    /** Waits up to [timeoutMs] for [preload]; returns immediately when nothing is loading. */
    suspend fun awaitPreload(timeoutMs: Long) {
        val d = preloaded ?: return
        withTimeoutOrNull(timeoutMs) { d.await() }
    }

    /**
     * Sets the preloaded look effect on the controller before a bind, so the session starts with
     * it. No-op when the effect is attached already, blocked, or the look is not loaded yet.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun attachIfReady() {
        if (ownsPreview) {
            preloaded?.takeIf { it.isCompleted }?.let { d ->
                processor?.setLook(runCatching { d.getCompleted() }.getOrNull())
                applied = prefs.lookId to prefs.intensity
            }
            return
        }
        if (effectAttached || previewEffectBlocked || rawSession) return
        val key = prefs.lookId to prefs.intensity
        val params = if (key.first == LookPreferences.NONE) {
            null
        } else {
            val d = preloaded ?: return
            if (!d.isCompleted || key != preloadedKey) return
            runCatching { d.getCompleted() }.getOrNull() ?: return
        }
        val p = processor ?: return
        p.setLook(params)
        try {
            cameraController.setEffects(setOf(LookEffect(p)))
            effectAttached = true
            applied = key
            _lookId.value = key.first
        } catch (e: RuntimeException) {
            Log.w(TAG, "Preview look effect not supported, photos only", e)
            disablePreviewEffect()
        }
    }

    /** True when a look is selected, so the preview effect matters. */
    val hasLook get() = repository.descriptor(prefs.lookId) != null

    /** Call before binding: drops the effect for a RAW session (without blocking it for later binds). */
    fun setRawSession(on: Boolean) {
        rawSession = on
        if (on && effectAttached) {
            runCatching { cameraController.setEffects(emptySet()) }
            effectAttached = false
        }
    }

    /** Applies the saved look (and intensity) to the preview; cheap if nothing changed. */
    fun refresh() {
        val id = prefs.lookId
        _lookId.value = id
        apply(id, prefs.intensity)
    }

    fun select(id: String) {
        prefs.lookId = id
        refresh()
    }

    /** Saves new style values and re-renders the preview when the style is the selected look. */
    fun setStyle(style: PhotoStyle) {
        prefs.style = style
        if (prefs.lookId == PhotoStyles.LOOK_ID) {
            applied = null
            refresh()
        }
    }

    /** ML steps for the next saved photo (portrait, beauty); set by the capture screen. */
    var captureOps: () -> AiOps? = { null }

    /** Queues [uri] for background rendering if a look, frame or ML step is active. */
    fun onPhotoSaved(uri: Uri) {
        val look = _lookId.value.takeIf { repository.descriptor(it) != null }?.let { prefs.captureLookId(it) }
        val frame = prefs.watermark
        val ops = runCatching { captureOps() }.getOrNull()?.takeUnless { it.isEmpty }
        if (look == null && frame == WatermarkTemplate.NONE && ops == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val intensity = prefs.intensity
        scope.launch(Dispatchers.IO) { ProcessingService.enqueue(app, uri, look, intensity, frame.key, ops) }
    }

    /**
     * Drops the preview effect for this session when the camera cannot bind it; looks are still
     * rendered onto saved photos.
     */
    fun disablePreviewEffect() {
        previewEffectBlocked = true
        if (effectAttached) {
            cameraController.setEffects(emptySet())
            effectAttached = false
        }
    }

    fun release() {
        processor?.release()
        processor = null
    }

    private fun apply(id: String, intensity: Float) {
        if (applied == id to intensity) return
        applied = id to intensity
        scope.launch {
            val params = withContext(Dispatchers.IO) {
                runCatching { repository.params(id, intensity) }
                    .onFailure { Log.e(TAG, "Cannot load look $id", it) }
                    .getOrNull()
            }
            if (applied != id to intensity) return@launch
            if (params == null) {
                // The attached effect passes frames through unchanged; removing it would make
                // CameraX rebind the whole session.
                processor?.setLook(null)
                return@launch
            }
            val p = processor ?: LookSurfaceProcessor().also { processor = it }
            p.setLook(params)
            if (!effectAttached && !previewEffectBlocked && !rawSession && !ownsPreview) {
                try {
                    cameraController.setEffects(setOf(LookEffect(p)))
                    effectAttached = true
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Preview look effect not supported, photos only", e)
                    disablePreviewEffect()
                }
            }
        }
    }

    private companion object {
        const val TAG = "LookController"
    }
}
