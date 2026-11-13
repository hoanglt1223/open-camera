/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tesseract on the device: rendered English and Vietnamese lines come back as text with boxes. */
@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {
    @Test
    fun recognisesRenderedPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val page = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
            typeface = Typeface.SERIF
        }
        canvas.drawText("Open Camera document scan", 80f, 200f, paint)
        canvas.drawText("Invoice number 2026 total 450", 80f, 360f, paint)
        canvas.drawText("Xin chào Việt Nam", 80f, 520f, paint)

        val engine = Ocr.open(context)
        assertNotNull("engine", engine)
        val started = System.nanoTime()
        val result = engine!!.use { it.recognize(page) }
        val ms = (System.nanoTime() - started) / 1_000_000
        android.util.Log.i("OcrDeviceTest", "OCR ${ms} ms: ${result.text.replace('\n', '|')} words=${result.words.size}")
        assertTrue(result.text, result.text.contains("Camera"))
        assertTrue(result.text, result.text.contains("2026"))
        assertTrue(result.text, result.text.contains("Việt"))
        val camera = result.words.first { it.text.contains("Camera") }
        assertTrue("box inside the line", camera.top in 120..220 && camera.left > 80)
    }
}
