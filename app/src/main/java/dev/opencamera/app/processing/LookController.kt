/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.camera.view.CameraController
import dev.opencamera.pipeline.ai.AiOps
import dev.opencamera.pipeline.looks.LookEffect
import dev.opencamera.pipeline.looks.LookParams
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
        if (repository.descriptor(key.first) == null) {
            preloaded = null
            return
        }
        if (!previewEffectBlocked && processor == null) {
            processor = runCatching { LookSurfaceProcessor() }.onFailure { Log.w(TAG, "No GL for looks", it) }.getOrNull()
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
        if (effectAttached || previewEffectBlocked) return
        val d = preloaded ?: return
        val key = preloadedKey ?: return
        if (!d.isCompleted || key != (prefs.lookId to prefs.intensity)) return
        val params = runCatching { d.getCompleted() }.getOrNull() ?: return
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

    /** ML steps for the next saved photo (portrait, beauty); set by the capture screen. */
    var captureOps: () -> AiOps? = { null }

    /** Queues [uri] for background rendering if a look, frame or ML step is active. */
    fun onPhotoSaved(uri: Uri) {
        val look = _lookId.value.takeIf { repository.descriptor(it) != null }
        val frame = prefs.watermark
        val ops = runCatching { captureOps() }.getOrNull()?.takeUnless { it.isEmpty }
        if (look == null && frame == WatermarkTemplate.NONE && ops == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        ProcessingService.enqueue(app, uri, look, prefs.intensity, frame.key, ops)
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
                if (effectAttached) {
                    cameraController.setEffects(emptySet())
                    effectAttached = false
                }
                processor?.setLook(null)
                return@launch
            }
            val p = processor ?: LookSurfaceProcessor().also { processor = it }
            p.setLook(params)
            if (!effectAttached && !previewEffectBlocked) {
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
