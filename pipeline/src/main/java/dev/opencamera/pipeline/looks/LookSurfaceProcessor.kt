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
    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()
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
            outputs.forEach { (out, s) -> EGL14.eglDestroySurface(display, s); out.close() }
            outputs.clear()
            makeCurrent(pbuffer)
            program.release()
            GLES30.glDeleteTextures(1, intArrayOf(oesTexture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            thread.quitSafely()
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        val st = SurfaceTexture(oesTexture)
        st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(st)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
        }
        st.setOnFrameAvailableListener({ drawFrame(it) }, handler)
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (released) {
            output.close()
            return
        }
        val surface = output.getSurface(executor) {
            outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
            output.close()
        }
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        outputs[output] = eglSurface
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
        for ((out, eglSurface) in outputs) {
            makeCurrent(eglSurface)
            out.updateTransformMatrix(outMatrix, texMatrix)
            program.draw(oesTexture, outMatrix, out.size.width, out.size.height)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(display, eglSurface)
        }
        countFrame()
    }

    private fun countFrame() {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
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
