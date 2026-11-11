/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.opencamera.app.develop.auto.AutoAdjust
import dev.opencamera.app.develop.auto.Histogram
import dev.opencamera.app.develop.mask.MaskRaster
import dev.opencamera.app.develop.model.AiShape
import dev.opencamera.app.develop.model.ColorRangeShape
import dev.opencamera.app.develop.model.LuminanceRangeShape
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.render.CpuDevelop
import dev.opencamera.app.develop.render.DevelopUniforms
import dev.opencamera.app.develop.render.Geometry
import dev.opencamera.app.develop.render.GlDevelop
import dev.opencamera.app.develop.render.Mat3
import dev.opencamera.pipeline.looks.LookParams
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Preview renderer of the editor. All GL work runs on one thread; requests are coalesced so a
 * fast slider drag renders only the newest stack. Falls back to [CpuDevelop] (with a smaller
 * preview) when GLES 3 is missing or the context is lost twice.
 */
class DevelopEngine(private val listener: Listener) {
    interface Listener {
        /** Called on the main thread after each render; [cpuFrame] is set only in CPU mode. */
        fun onRendered(stats: RenderStats, cpuFrame: Bitmap?)
        fun onHistogram(h: Histogram)
        fun onGpuUnavailable(reason: String)
    }

    data class RenderStats(val ms: Long, val outWidth: Int, val outHeight: Int, val gpu: Boolean)

    private val thread = HandlerThread("develop-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var glr: GlDevelop? = null
    private var source: GlDevelop.Source? = null
    private var thumbSource: GlDevelop.Source? = null
    private var output = 0
    private var outW = 0
    private var outH = 0
    private var before = 0
    private var cpu: CpuDevelop.Prepared? = null
    private var proxy: Bitmap? = null
    private var proxyPixels: IntArray? = null
    private var maskW = 0
    private var maskH = 0
    private var maskKey: List<LocalMask>? = null
    private var maskMaps: List<FloatArray> = emptyList()
    private var failures = 0

    @Volatile var atmosphere: FloatArray = floatArrayOf(1f, 1f, 1f)
        private set
    @Volatile var wide = false
    @Volatile var look: LookParams? = null
    /** Coverage map (data, width, height) of an AI mask, or null when unavailable. */
    var aiMasks: (AiShape) -> Triple<FloatArray, Int, Int>? = { null }

    private var pending: EditStack? = null
    private var pendingDraft = false
    private var busy = false
    private var view: View? = null
    private val timings = ArrayList<Long>()

    data class View(val surface: Surface, val width: Int, val height: Int, val viewToImage: Mat3, val split: Float, val warn: Boolean, val background: Int)

    val gpu: Boolean get() = glr != null

    /** Proxy pixels (ARGB) and size, for Auto, range masks and hit testing. */
    fun proxyPixels(): IntArray? = proxyPixels
    val proxyWidth get() = proxy?.width ?: 0
    val proxyHeight get() = proxy?.height ?: 0

    /** Creates the context and compiles shaders while the photo is still decoding. */
    fun prewarm() = gl.post {
        if (glr != null || failures >= 2) return@post
        val t0 = SystemClock.elapsedRealtime()
        glr = runCatching { GlDevelop.create() }.getOrNull()
        val g = glr ?: return@post
        runCatching {
            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            val s = g.createSource(b)
            val t = g.newTarget(16, 16)
            g.develop(DevelopUniforms.from(EditStack(), null), s, s.lin, 16, FULL, Geometry.frame(EditStack(), 16, 16), FULL, 16, 16, t, 16, 16)
            g.finish()
            g.deleteTexture(t); g.release(s)
        }
        Log.i(TAG, "GL prewarm ${SystemClock.elapsedRealtime() - t0} ms")
    }

    fun open(bitmap: Bitmap, onReady: () -> Unit) = gl.post {
        val t00 = SystemClock.elapsedRealtime()
        releaseSource()
        proxy = bitmap
        val px = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        proxyPixels = px
        val ta = SystemClock.elapsedRealtime()
        // Haze colour only matters for dehaze; a coarse sample is enough.
        atmosphere = AutoAdjust.atmosphere(AutoAdjust.samples(px, 32_768))
        Log.i(TAG, "open: pixels ${ta - t00} ms, atmosphere ${SystemClock.elapsedRealtime() - ta} ms")
        maskW = max(1, bitmap.width / 2)
        maskH = max(1, bitmap.height / 2)
        maskKey = null
        if (glr == null && failures < 2) {
            glr = try {
                GlDevelop.create()
            } catch (e: Throwable) {
                Log.w(TAG, "GL unavailable", e)
                failures = 2
                main.post { listener.onGpuUnavailable(e.message ?: e.toString()) }
                null
            }
        }
        val g = glr
        if (g != null) {
            try {
                if (!windowAttached) { g.attachWindow(view?.surface); windowAttached = view != null }
                val t0 = SystemClock.elapsedRealtime()
                source = g.createSource(bitmap)
                val small = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width * 256 / max(bitmap.width, bitmap.height)),
                    max(1, bitmap.height * 256 / max(bitmap.width, bitmap.height)), true)
                thumbSource = g.createSource(small)
                g.finish()
                Log.i(TAG, "Source ${bitmap.width}x${bitmap.height} ${bitmap.config} ready in ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (e: Throwable) {
                fallBack(e)
            }
        }
        if (glr == null) prepareCpu(bitmap, px)
        Log.i(TAG, "open ${SystemClock.elapsedRealtime() - t00} ms")
        main.post(onReady)
    }

    private fun prepareCpu(bitmap: Bitmap, px: IntArray) {
        // The CPU preview runs at <= 1024 px to stay interactive.
        val s = minOf(1f, 1024f / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * s).roundToInt())
        val h = max(1, (bitmap.height * s).roundToInt())
        val small = if (s < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val spx = if (small === bitmap) px else IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        cpu = CpuDevelop.prepare(spx, w, h)
    }

    private fun fallBack(e: Throwable) {
        Log.w(TAG, "GPU render failed, switching to CPU", e)
        failures++
        runCatching { glr?.release() }
        glr = null
        source = null; thumbSource = null; output = 0; before = 0
        if (failures < 2) {
            // One retry with a fresh context (e.g. after a context loss).
            glr = runCatching { GlDevelop.create() }.getOrNull()
            val p = proxy
            if (glr != null && p != null) {
                try {
                    source = glr!!.createSource(p)
                    glr!!.attachWindow(view?.surface)
                    windowAttached = view != null
                    return
                } catch (e2: Throwable) {
                    runCatching { glr?.release() }
                    glr = null
                }
            }
        }
        failures = 2
        main.post { listener.onGpuUnavailable(e.message ?: e.toString()) }
        val p = proxy
        val px = proxyPixels
        if (p != null && px != null) prepareCpu(p, px)
    }

    private var windowAttached = false

    fun attach(v: View?) = gl.post {
        val old = view
        view = v
        val g = glr
        if (g != null && (v?.surface !== old?.surface || !windowAttached)) {
            g.attachWindow(v?.surface)
            windowAttached = v != null
        }
        present()
    }

    /**
     * Renders [stack]; while a render runs only the newest request is kept. [draft] renders at
     * screen resolution (during a slider drag); the full proxy render follows on release.
     */
    fun render(stack: EditStack, draft: Boolean = false) {
        synchronized(this) {
            pending = stack
            pendingDraft = draft
            if (busy) return
            busy = true
        }
        gl.post { drain() }
    }

    /** Screen-resolution frame first (fast first paint), then the full proxy. */
    fun renderProgressive(stack: EditStack) = gl.post {
        renderNow(stack, draft = true)
        renderNow(stack, draft = false)
    }

    private fun drain() {
        while (true) {
            var draft = false
            val s = synchronized(this) {
                val p = pending
                draft = pendingDraft
                pending = null
                if (p == null) busy = false
                p
            } ?: return
            renderNow(s, draft)
        }
    }

    private fun renderNow(stack: EditStack, draft: Boolean = false) {
        val p = proxy ?: return
        val t0 = SystemClock.elapsedRealtime()
        val frame = Geometry.frame(stack, p.width, p.height)
        val u = DevelopUniforms.from(stack, look, atmosphere, wide)
        updateMasks(stack)
        val g = glr
        val src = source
        if (g != null && src != null) {
            try {
                // Draft: no more pixels than the view shows.
                val vlong = view?.let { max(it.width, it.height) } ?: 0
                val ds = if (draft && vlong > 0) minOf(1f, vlong.toFloat() / max(frame.outWidth, frame.outHeight)) else 1f
                val tw = max(1, (frame.outWidth * ds).roundToInt())
                val th = max(1, (frame.outHeight * ds).roundToInt())
                if (output == 0 || outW != tw || outH != th) {
                    g.deleteTexture(output)
                    outW = tw; outH = th
                    output = g.newTarget(outW, outH)
                }
                val tu = SystemClock.elapsedRealtime()
                // Effects sized relative to the output (vignette, grain) use the full proxy size.
                g.develop(u, src, src.lin, src.width, FULL, frame, FULL, frame.outWidth, frame.outHeight, output, outW, outH)
                g.finish()
                val td = SystemClock.elapsedRealtime()
                present()
                g.finish()
                if (timings.size == 5 || timings.isEmpty()) Log.i(TAG, "split: ${outW}x$outH prep ${tu - t0} develop ${td - tu} present ${SystemClock.elapsedRealtime() - td} ms")
                val ms = SystemClock.elapsedRealtime() - t0
                record(ms)
                val stats = RenderStats(ms, frame.outWidth, frame.outHeight, true)
                main.post { listener.onRendered(stats, null) }
                if (SystemClock.elapsedRealtime() - lastHistogram > HISTOGRAM_INTERVAL_MS && synchronized(this) { pending == null }) {
                    lastHistogram = SystemClock.elapsedRealtime()
                    val small = g.readScaled(output, outW, outH, 256)
                    val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
                    val h = Histogram.of(px)
                    main.post { listener.onHistogram(h) }
                }
                return
            } catch (e: Throwable) {
                fallBack(e)
                if (glr != null) { renderNow(stack); return }
            }
        }
        val c = cpu ?: return
        val f = Geometry.frame(stack, c.lin.w, c.lin.h)
        val maps = maskMaps.map { MaskRaster.resize(it, maskW, maskH, c.lin.w, c.lin.h) }
        val px = CpuDevelop.render(u, c, f, f.outWidth, f.outHeight, maps)
        val bmp = Bitmap.createBitmap(px, f.outWidth, f.outHeight, Bitmap.Config.ARGB_8888)
        val ms = SystemClock.elapsedRealtime() - t0
        val stats = RenderStats(ms, f.outWidth, f.outHeight, false)
        val h = Histogram.of(px)
        main.post {
            listener.onRendered(stats, bmp)
            listener.onHistogram(h)
        }
    }

    private var lastHistogram = 0L
    private var loggedPresent = false

    private fun record(ms: Long) {
        timings += ms
        if (timings.size >= 10) {
            val sorted = timings.sorted()
            Log.i(TAG, "Develop render ${outW}x$outH median ${sorted[sorted.size / 2]} ms (n=${sorted.size}, max ${sorted.last()})")
            timings.clear()
        }
    }

    /** Re-rasterizes masks only when they changed. Call on the GL thread. */
    private fun updateMasks(stack: EditStack) {
        val active = stack.masks.filter { it.enabled }.take(DevelopUniforms.MASK_SLOTS)
        if (active == maskKey) return
        maskKey = active
        if (active.isEmpty()) { maskMaps = emptyList(); return }
        val p = proxy ?: return
        val px = proxyPixels ?: return
        val needsPixels = active.any { m -> m.components.any { it.shape is LuminanceRangeShape || it.shape is ColorRangeShape } }
        val small = if (needsPixels) {
            IntArray(maskW * maskH) { i -> px[((i / maskW) * p.height / maskH) * p.width + (i % maskW) * p.width / maskW] }
        } else null
        maskMaps = active.map { m ->
            MaskRaster.render(m, maskW, maskH, small) { ai -> aiMasks(ai)?.let { MaskRaster.resize(it.first, it.second, it.third, maskW, maskH) } }
        }
        glr?.setMasks(MaskRaster.pack(maskMaps, maskW, maskH), maskW, maskH)
    }

    /** The coverage of mask [index] (for the overlay), at mask resolution. */
    fun maskCoverage(index: Int): Triple<FloatArray, Int, Int>? = maskMaps.getOrNull(index)?.let { Triple(it, maskW, maskH) }

    /** Renders [stack] (or the original when null) into the comparison texture. */
    fun renderBefore(stack: EditStack?) = gl.post {
        val g = glr ?: return@post
        val src = source ?: return@post
        val p = proxy ?: return@post
        try {
            val s = stack ?: EditStack()
            // Before keeps the current framing so the split lines up.
            val frame = Geometry.frame(s, p.width, p.height)
            if (outW == 0) return@post
            g.deleteTexture(before)
            before = g.newTarget(outW, outH)
            g.develop(DevelopUniforms.from(s.copy(masks = emptyList()), look, atmosphere, wide), src, src.lin, src.width, FULL, frame, FULL,
                frame.outWidth, frame.outHeight, before, outW, outH)
            present()
        } catch (e: Throwable) {
            Log.w(TAG, "Before render failed", e)
        }
    }

    fun clearBefore() = gl.post {
        glr?.deleteTexture(before)
        before = 0
        present()
    }

    private fun present() {
        val v = view ?: return
        val g = glr ?: return
        if (output == 0) return
        if (!loggedPresent) { loggedPresent = true; Log.i(TAG, "present ${v.width}x${v.height} out=${outW}x$outH") }
        runCatching { g.present(output, before, v.width, v.height, v.viewToImage, if (before != 0) v.split else -1f, v.warn, v.background) }
            .onFailure { Log.w(TAG, "present failed", it) }
    }

    /** 256 px preview of [stack] (preset thumbnails). Blocking callback on the main thread. */
    fun thumbnail(stack: EditStack, onDone: (Bitmap?) -> Unit) = gl.post {
        val g = glr
        val ts = thumbSource
        val bmp = try {
            if (g != null && ts != null) {
                val frame = Geometry.frame(stack, ts.width, ts.height)
                val t = g.newTarget(frame.outWidth, frame.outHeight)
                g.develop(DevelopUniforms.from(stack.copy(masks = emptyList()), look, atmosphere, wide), ts, ts.lin, ts.width, FULL, frame, FULL,
                    frame.outWidth, frame.outHeight, t, frame.outWidth, frame.outHeight)
                g.read(t, frame.outWidth, frame.outHeight).also { g.deleteTexture(t) }
            } else null
        } catch (e: Throwable) {
            null
        }
        main.post { onDone(bmp) }
    }

    /** Runs [block] on the render thread (look loading, AI masks). */
    fun post(block: () -> Unit) = gl.post(block)

    private fun releaseSource() {
        val g = glr ?: return
        source?.let { g.release(it) }
        thumbSource?.let { g.release(it) }
        g.deleteTexture(output); g.deleteTexture(before)
        source = null; thumbSource = null; output = 0; before = 0
    }

    fun release() {
        gl.post {
            runCatching { releaseSource(); glr?.release() }
            glr = null
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "DevelopEngine"
        private val FULL = floatArrayOf(0f, 0f, 1f, 1f)
        private const val HISTOGRAM_INTERVAL_MS = 150L
    }
}
