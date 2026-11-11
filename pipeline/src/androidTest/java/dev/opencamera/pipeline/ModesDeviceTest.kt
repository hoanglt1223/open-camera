/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencamera.pipeline.document.DocumentProcessor
import dev.opencamera.pipeline.document.PdfExporter
import dev.opencamera.pipeline.panorama.PanoramaProcessor
import dev.opencamera.pipeline.video.BitmapVideoEncoder
import dev.opencamera.pipeline.video.VideoRetimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/** On-device checks for the OpenCV and MediaCodec paths of the extra modes. */
@RunWith(AndroidJUnit4::class)
class ModesDeviceTest {
    private val cache: File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Before
    fun loadOpenCv() = assertTrue("OpenCV must load", OpenCv.available)

    /** A wide textured scene: random blocks so phase correlation has structure to lock onto. */
    private fun scene(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        val rnd = Random(7)
        c.drawColor(Color.DKGRAY)
        repeat(600) {
            p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val x = rnd.nextInt(width).toFloat()
            val y = rnd.nextInt(height).toFloat()
            c.drawRect(x, y, x + 20 + rnd.nextInt(80), y + 20 + rnd.nextInt(80), p)
        }
        return bmp
    }

    @Test
    fun panoramaStitchesShiftedFrames() {
        val world = scene(2600, 900)
        val frameW = 600
        val step = 400
        val focal = 1500f
        val frames = (0 until 5).map { i ->
            val crop = Bitmap.createBitmap(world, i * step, 0, frameW, 900)
            // Yaw decreases while sweeping right; the stitcher only needs the gyro prior.
            PanoramaProcessor.Frame(crop, -Math.toDegrees((i * step / focal).toDouble()).toFloat())
        }
        val start = System.nanoTime()
        val pano = PanoramaProcessor.stitch(frames, focal)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertNotNull(pano)
        pano!!
        android.util.Log.i("ModesDeviceTest", "panorama ${pano.width}x${pano.height} in $ms ms")
        // Cylinder warp shrinks spacing a little; the width must be close to the swept span.
        assertTrue("width ${pano.width}", abs(pano.width - (4 * step + frameW)) < 200)
        assertTrue(pano.height in 700..900)
    }

    @Test
    fun documentDetectsAndRectifiesPage() {
        val bmp = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(60, 50, 40))
        val page = Path().apply {
            moveTo(250f, 200f); lineTo(1000f, 260f); lineTo(1050f, 1400f); lineTo(180f, 1350f); close()
        }
        c.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 225, 215) })
        val quad = DocumentProcessor.detect(bmp)
        assertNotNull("page outline", quad)
        val expected = floatArrayOf(250f, 200f, 1000f, 260f, 1050f, 1400f, 180f, 1350f)
        for (i in 0 until 8) assertEquals("corner $i", expected[i], quad!![i], 25f)
        val out = DocumentProcessor.process(bmp, quad, DocumentProcessor.Enhance.COLOR)
        assertTrue(out.width in 700..900 && out.height in 1050..1250)
        // Whitened: the page centre is near white.
        val centre = out.getPixel(out.width / 2, out.height / 2)
        assertTrue("centre ${Integer.toHexString(centre)}", Color.red(centre) > 235)
        val pdf = ByteArrayOutputStream()
        PdfExporter.write(listOf(out, out), pdf)
        assertTrue(String(pdf.toByteArray(), 0, 5) == "%PDF-")
    }

    @Test
    fun encoderAndRetimerWritePlayableFiles() {
        val file = File(cache, "enc-test.mp4")
        val enc = BitmapVideoEncoder(file, 1280, 720, 30)
        val frame = scene(1280, 720)
        repeat(60) { enc.addFrame(frame) }
        assertTrue(enc.finish())
        assertEquals(2.0, durationSec(file), 0.15)

        val slow = File(cache, "retime-test.mp4")
        file.inputStream().use { assertTrue(VideoRetimer.retime(it.fd, slow, 4)) }
        assertEquals(8.0, durationSec(slow), 0.5)
        file.delete(); slow.delete()
    }

    private fun durationSec(file: File): Double {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val f = ex.getTrackFormat(0)
            return f.getLong(MediaFormat.KEY_DURATION) / 1e6
        } finally {
            ex.release()
        }
    }
}
