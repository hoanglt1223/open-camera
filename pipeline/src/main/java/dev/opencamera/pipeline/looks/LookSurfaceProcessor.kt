/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor

/**
 * CameraX [SurfaceProcessor] that renders the preview stream through the current
 * look on a dedicated GL thread.
 */
class LookSurfaceProcessor : SurfaceProcessor {
    private val thread = HandlerThread("LookGl").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private lateinit var config: EGLConfig
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var program: GlLookProgram
    private var oesTexture = 0
    private class Output(
        val egl: EGLSurface,
        val width: Int,
        val height: Int,
        val matrix: (FloatArray, FloatArray) -> Unit,
        /** Camera timestamps are in the sensor timebase; a view surface must show frames at once. */
        val stampFrames: Boolean = true,
    )

    private val outputs = LinkedHashMap<Any, Output>()
    private val texMatrix = FloatArray(16)
    private val outMatrix = FloatArray(16)
    private var released = false

    private var frames = 0
    private var fpsWindowStart = 0L

    /** Latest measured preview rate, for diagnostics. */
    @Volatile
    var fps = 0f
        private set

    init {
        handler.post { initGl() }
    }

    /** Switches the look; null renders the camera stream unchanged. */
    fun setLook(params: LookParams?) {
        handler.post { if (!released) program.setLook(params) }
    }

    fun release() {
        handler.post {
            if (released) return@post
            released = true
            outputs.forEach { (key, out) -> EGL14.eglDestroySurface(display, out.egl); (key as? SurfaceOutput)?.close() }
            outputs.clear()
            makeCurrent(pbuffer)
            program.release()
            GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            // The default display is shared by every GL user in the process; terminating it here
            // would invalidate the editor's contexts.
            EGL14.eglReleaseThread()
            thread.quitSafely()
        }
    }

    override fun onInputSurface(request: SurfaceRequest) = provideInput(request, null)

    /** Feeds [request] from a new input texture; [onReleased] runs (GL thread) once the camera is done with it. */
    fun provideInput(request: SurfaceRequest, onReleased: (() -> Unit)?) {
        if (released) {
            request.willNotProvideSurface()
            onReleased?.invoke()
            return
        }
        // A new session starts a new rate window; the old one says nothing about it.
        frames = 0
        fpsWindowStart = 0L
        fps = 0f
        val st = SurfaceTexture(oesTexture)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            onReleased?.invoke()
        }
        st.setOnFrameAvailableListener({ drawFrame(it) }, handler)
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released) {
            output.close()
            return
        }
        val surface = output.getSurface(executor) {
            outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it.egl) }
            output.close()
        }
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        outputs[output] = Output(eglSurface, output.size.width, output.size.height, output::updateTransformMatrix)
    }

    /**
     * Draws every frame onto [surface] (for example a preview view's surface) with the input's own
     * transform, so the consumer sees the same geometry as a direct camera stream. Runs on the GL
     * thread; [onFailed] is told when the window surface cannot be used.
     */
    fun addOutput(key: Any, surface: Surface, width: Int, height: Int, onFailed: (Throwable) -> Unit) {
        handler.post {
            if (released) return@post
            try {
                val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                check(egl != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface 0x%x".format(EGL14.eglGetError()) }
                outputs[key] = Output(egl, width, height, { out, tex -> System.arraycopy(tex, 0, out, 0, 16) }, stampFrames = false)
            } catch (e: RuntimeException) {
                onFailed(e)
            }
        }
    }

    fun removeOutput(key: Any) {
        handler.post {
            if (released) return@post
            outputs.remove(key)?.let {
                runCatching { makeCurrent(pbuffer) }
                EGL14.eglDestroySurface(display, it.egl)
            }
        }
    }

    private fun drawFrame(st: SurfaceTexture) {
        if (released) return
        makeCurrent(pbuffer)
        try {
            st.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        st.getTransformMatrix(texMatrix)
        val dead = ArrayList<Any>(0)
        for ((key, out) in outputs) {
            // A window surface can go away before its owner tells us (view detached); drop it
            // instead of throwing on the GL thread.
            if (!EGL14.eglMakeCurrent(display, out.egl, out.egl, context)) {
                Log.w(TAG, "Output surface lost (0x%x)".format(EGL14.eglGetError()))
                dead += key
                continue
            }
            out.matrix(outMatrix, texMatrix)
            program.draw(oesTexture, outMatrix, out.width, out.height)
            if (out.stampFrames) EGLExt.eglPresentationTimeANDROID(display, out.egl, st.timestamp)
            EGL14.eglSwapBuffers(display, out.egl)
        }
        if (dead.isNotEmpty()) {
            makeCurrent(pbuffer)
            dead.forEach { k -> outputs.remove(k)?.let { EGL14.eglDestroySurface(display, it.egl) } }
        }
        countFrame()
    }

    private fun countFrame() {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) {
            fpsWindowStart = now
            Log.i(TAG, "first look frame")
        }
        frames++
        if (now - fpsWindowStart >= 2000) {
            fps = frames * 1000f / (now - fpsWindowStart)
            Log.d(TAG, "preview fps %.1f".format(fps))
            frames = 0
            fpsWindowStart = now
        }
    }

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "No EGL config"
        }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrent(pbuffer)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTexture = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        program = GlLookProgram()
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    private companion object {
        const val TAG = "LookSurfaceProcessor"
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

/** Preview-only effect; stills are rendered by [NativeLookRenderer] with the same math. */
class LookEffect(val processor: LookSurfaceProcessor) : CameraEffect(
    PREVIEW, processor.executor, processor, { Log.e("LookEffect", "Effect error", it) },
)
