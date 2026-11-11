/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencamera.pipeline.StillProcessor
import dev.opencamera.pipeline.watermark.ExifFields
import dev.opencamera.pipeline.watermark.WatermarkLayout
import dev.opencamera.pipeline.watermark.WatermarkRenderer
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Device checks: the native still renderer and the preview shader both match the
 * [LookMath] reference, plus full-resolution timings.
 */
@RunWith(AndroidJUnit4::class)
class LookDeviceTest {
    /** A smooth, strongly non-linear LUT with every runtime effect switched on. */
    private fun testParams(): LookParams {
        val n = 33
        val data = FloatArray(n * n * n * 3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val fr = r / 32f; val fg = g / 32f; val fb = b / 32f
            data[i++] = (0.8f * fr + 0.2f * fg * fg + 0.05f * sin(6f * fb)).coerceIn(0f, 1f)
            data[i++] = (0.9f * fg * fg + 0.1f * fb).coerceIn(0f, 1f)
            data[i++] = (0.3f + 0.6f * fb - 0.2f * fr * fg).coerceIn(0f, 1f)
        }
        val d = LookDescriptor(
            "t", "t", "", "lut.cube", 0.85f, listOf(0f to 0.02f, 0.3f to 0.25f, 0.7f to 0.78f, 1f to 1f),
            highlightRolloff = 0.4f, grainAmount = 0.3f, grainSize = 2f, vignetteAmount = 0.3f, vignetteSoftness = 0.6f,
        )
        return LookParams.of(d, Lut3d(n, data))
    }

    private fun testBitmap(w: Int, h: Int): Bitmap {
        // Filled row by row: a 64 MP IntArray would not fit the Java heap.
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                row[x] = 0xff000000.toInt() or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x xor y) and 0xff)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }

    private fun maxChannelDiff(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) for (s in intArrayOf(0, 8, 16)) {
            m = max(m, abs(((a[i] shr s) and 0xff) - ((b[i] shr s) and 0xff)))
        }
        return m
    }

    @Test
    fun nativeMatchesReference() {
        val w = 301; val h = 203
        val params = testParams()
        val bmp = testBitmap(w, h)
        val expected = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        LookMath.applyArgb(params, expected, w, h)
        NativeLookRenderer.apply(bmp, params)
        val actual = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val diff = maxChannelDiff(expected, actual)
        Log.i(TAG, "native vs reference max diff $diff")
        assertTrue("max diff $diff", diff <= 1)
    }

    @Test
    fun shaderMatchesReference() {
        val w = 256; val h = 192
        val params = testParams()
        val src = testBitmap(w, h)
        val expected = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        LookMath.applyArgb(params, expected, w, h)

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(
            display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ), 0, configs, 0, 1, IntArray(1), 0,
        )
        val ctx = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(display, surface, surface, ctx)
        try {
            // GL rows run bottom-up while the shader counts y from the top, as on a
            // window surface: upload flipped so shader y equals bitmap row.
            val flipped = Bitmap.createBitmap(src, 0, 0, w, h, Matrix().apply { setScale(1f, -1f) }, false)
            val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, flipped, 0)
            val program = GlLookProgram(externalInput = false)
            program.setLook(params)
            val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
            program.draw(tex, identity, w, h)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val actual = IntArray(w * h)
            for (row in 0 until h) for (x in 0 until w) {
                val o = (row * w + x) * 4
                val r = buf.get(o).toInt() and 0xff
                val g = buf.get(o + 1).toInt() and 0xff
                val b = buf.get(o + 2).toInt() and 0xff
                // GL row `row` holds bitmap row h - 1 - row.
                actual[(h - 1 - row) * w + x] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            val diff = maxChannelDiff(expected, actual)
            var sum = 0L
            for (i in expected.indices) for (s in intArrayOf(0, 8, 16)) {
                sum += abs(((expected[i] shr s) and 0xff) - ((actual[i] shr s) and 0xff))
            }
            Log.i(TAG, "shader vs reference max diff $diff mean ${sum.toDouble() / (w * h * 3)}")
            assertTrue("max diff $diff", diff <= 2)
            program.release()
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
        }
    }

    @Test
    fun fullResolutionTimings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val params = testParams()
        val processor = StillProcessor(context.contentResolver)
        for ((label, w, h) in listOf(Triple("12MP", 4000, 3000), Triple("48MP", 8000, 6000), Triple("64MP", 9248, 6944))) {
            val out = File(context.cacheDir, "perf-$label.jpg")
            // Encode a source JPEG once so decode is measured on real data.
            val srcFile = File(context.cacheDir, "src-$label.jpg")
            testBitmap(w, h).also { b ->
                srcFile.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                b.recycle()
            }
            for (frame in listOf(WatermarkTemplate.NONE, WatermarkTemplate.EXIF_STRIP)) {
                val t0 = SystemClock.elapsedRealtime()
                val bmp = BitmapFactory.decodeFile(srcFile.path, BitmapFactory.Options().apply { inMutable = true })
                val t = processor.processBitmap(bmp, null, params, frame, "Test", out, t0)
                Log.i(
                    TAG, "PERF $label ${t.width}x${t.height} frame=$frame decode=${t.decodeMs} look=${t.lookMs} " +
                        "frame=${t.frameMs} encode=${t.encodeMs} total=${t.totalMs} ms"
                )
            }
            out.delete(); srcFile.delete()
        }
    }

    @Test
    fun frameFollowsExifRotation() {
        // Stored landscape, displayed portrait (EXIF 90): the frame is laid out upright
        // and the result stays in stored orientation.
        val framed = WatermarkRenderer.render(
            testBitmap(640, 480), 90, WatermarkTemplate.FILM_BORDER, ExifFields(model = null, iso = 100),
        )
        val upright = WatermarkLayout.compute(WatermarkTemplate.FILM_BORDER, 480, 640)!!
        assertTrue(framed.width == upright.canvasHeight && framed.height == upright.canvasWidth)
        // Turning the stored image 90 degrees clockwise moves its right edge to the
        // bottom, so the wide bottom band must sit on the stored right edge.
        assertTrue(framed.getPixel(framed.width - 3, framed.height / 2) == android.graphics.Color.WHITE)
        assertTrue(framed.getPixel(framed.width / 2, framed.height / 2) != android.graphics.Color.WHITE)
    }

    private companion object {
        const val TAG = "LookDeviceTest"
    }
}
