/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import dev.opencamera.app.R
import dev.opencamera.app.ui.skin.SkinCrashGuard
import dev.opencamera.app.ui.skin.SkinRepository
import dev.opencamera.app.ui.skin.SkinTokens
import dev.opencamera.app.ui.skin.Skins
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Follows the activity lifecycle with a registry of its own (see [OverlayHost.attach]). */
private class OverlayLifecycle(private val source: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val observer = LifecycleEventObserver { _, event ->
        runCatching { registry.handleLifecycleEvent(event) }
    }
    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = source.currentState.coerceAtLeast(Lifecycle.State.CREATED)
        source.addObserver(observer)
    }

    fun release() {
        source.removeObserver(observer)
        runCatching { if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED }
    }
}

/** One-time backdrop blur for Glass sheets; never applied to the live preview. */
object BackdropBlur {
    var reporter: ((Double) -> Unit)? = null

    /** Two-step downscale then upscale with filtering: a soft blur for a few hundred µs. */
    fun static(src: Bitmap): Bitmap {
        val w = 24
        val h = (src.height.toLong() * w / src.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val tiny = src.scale(w * 2, h * 2).scale(w, h)
        return tiny.scale(w * 6, h * 6)
    }

    fun report(ms: Double) {
        reporter?.invoke(ms)
    }
}

/**
 * Frame for the Compose layer: an exception while measuring, laying out, drawing or
 * dispatching touches is caught here and reported instead of taking the camera down.
 */
@SuppressLint("ViewConstructor")
private class SafeHost(context: Context, private val onError: (Throwable) -> Unit) : FrameLayout(context) {
    var failed = false
        private set

    private fun fail(e: Throwable) {
        if (failed) return
        failed = true
        onError(e)
    }

    /**
     * Compose runs its first composition while the view is attached, outside any recomposer.
     * The content view is therefore added only once this frame is attached, inside a try.
     */
    var content: android.view.View? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val child = content ?: return@post
            if (failed || child.parent != null) return@post
            try {

                addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } catch (e: RuntimeException) {
                fail(e)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = try {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    } catch (e: RuntimeException) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        fail(e)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = try {
        super.onLayout(changed, left, top, right, bottom)
    } catch (e: RuntimeException) {
        fail(e)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (failed) return
        val count = canvas.save()
        try {
            super.dispatchDraw(canvas)
        } catch (e: RuntimeException) {
            fail(e)
        } finally {
            canvas.restoreToCount(count)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (failed) false else try {
        super.dispatchTouchEvent(ev)
    } catch (e: RuntimeException) {
        fail(e)
        false
    }
}

/**
 * Hosts the skinned Compose overlay above the preview. Fallback chain: a skin that fails
 * (composition, layout, draw, touch, or a crash on the previous launch) is replaced by the
 * other skin; when both failed the classic View layout comes back via [onLegacy].
 */
class OverlayHost(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val parent: ViewGroup,
    private val insertIndex: Int,
    private val repo: SkinRepository,
    private val state: StateFlow<CameraUiState>,
    private val actions: CameraUiActions,
    private val onSkin: (SkinTokens) -> Unit,
    private val onLegacy: () -> Unit,
    /** Debug builds only: "all" or a skin id makes that skin throw while composing. */
    private val debugFail: String?,
) {
    val ui = OverlayUiState()
    var active = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var host: SafeHost? = null
    private var owner: OverlayLifecycle? = null
    private var scope: CoroutineScope? = null
    private var currentSkin: String = Skins.DEFAULT.id
    private val tried = mutableSetOf<String>()

    /** Returns false when the View layout must be used for this launch. */
    fun start(): Boolean {
        val decision = repo.crashGuard.onLaunch(repo.skin.value.id)
        if (decision is SkinCrashGuard.Decision.Legacy) {
            Log.w(TAG, "Skin layer disabled after repeated failures, using the View layout")
            return false
        }
        decision as SkinCrashGuard.Decision.Compose
        if (decision.resetFrom != null) {
            Toast.makeText(context, R.string.ui_skin_reset, Toast.LENGTH_LONG).show()
        }
        BackdropBlur.reporter = repo::reportBlurCost
        return attach()
    }

    private fun attach(): Boolean {
        val ok = runCatching {
            val safe = SafeHost(context) { e -> main.post { onRuntimeFailure(e) } }
            // Compose composes first inside a lifecycle callback; an exception there leaves that
            // LifecycleRegistry inconsistent. A private registry keeps the activity's one clean.
            val owner = OverlayLifecycle(lifecycle)
            safe.setViewTreeLifecycleOwner(owner)
            this.owner = owner
            val job = Job()
            val handler = CoroutineExceptionHandler { _, e -> main.post { onRuntimeFailure(e) } }
            val ctx = AndroidUiDispatcher.Main + job + handler
            val recomposer = Recomposer(ctx)
            val newScope = CoroutineScope(ctx)
            newScope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(context).apply {
                setParentCompositionContext(recomposer)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { Root() }
            }
            safe.content = view
            currentSkin = repo.skin.value.id
            repo.crashGuard.beforeContent(currentSkin)
            parent.addView(
                safe, insertIndex.coerceIn(0, parent.childCount),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            host = safe
            scope = newScope
        }.onFailure { Log.e(TAG, "Compose overlay could not be created", it) }.isSuccess
        active = ok
        if (!ok) repo.crashGuard.clear()
        return ok
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val tokens by repo.skin.collectAsState()
        val blur by repo.blurPolicy.collectAsState()
        val s by state.collectAsState()

        LaunchedEffect(tokens.id) {
            currentSkin = tokens.id
            onSkin(tokens)
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            repo.crashGuard.onFirstFrame()
        }
        val scale = remember { repo.animatorScale() }
        Crossfade(tokens, animationSpec = tween((240 * scale).toInt()), label = "skin") { t ->
            if (debugFail == "all" || debugFail == t.id) error("Injected skin failure for ${t.id}")
            CompositionLocalProvider(LocalSkin provides remember(t, blur) { Skin(t, blur, scale) }) {
                CameraOverlay(s, actions, ui)
            }
        }
    }

    private fun onRuntimeFailure(e: Throwable) {
        if (!active) return
        Log.e(TAG, "Skin ${currentSkin} failed, falling back", e)
        tried += currentSkin
        detach()
        val next = repo.crashGuard.onRuntimeFailure(currentSkin, tried)
        if (next != null && attach()) {
            Toast.makeText(context, R.string.ui_skin_reset, Toast.LENGTH_LONG).show()
            return
        }
        active = false
        Toast.makeText(context, R.string.ui_skin_legacy, Toast.LENGTH_LONG).show()
        onLegacy()
    }

    private fun detach() {
        host?.let { h ->
            runCatching { (h.content as? ComposeView)?.disposeComposition() }
            runCatching { parent.removeView(h) }.onFailure { Log.w(TAG, "Overlay removal", it) }
            h.content = null
        }
        owner?.release()
        owner = null
        scope?.cancel()
        host = null
        scope = null
        active = false
    }

    fun destroy() {
        detach()
        BackdropBlur.reporter = null
    }

    companion object {
        private const val TAG = "OverlayHost"

        /** Returns [extra] only on debuggable builds (fault injection and test hooks). */
        fun debugFailure(context: Context, extra: String?): String? =
            extra.takeIf { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    }
}


