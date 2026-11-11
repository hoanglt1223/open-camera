/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.opencamera.pipeline.OpenCv
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Encodes app-made frames (time-lapse, hyperlapse, time freeze) to an H.264 MP4 with explicit
 * timestamps. Frames go in as YUV through [MediaCodec.getInputImage], which works with every
 * flexible-YUV encoder without an EGL context. Not thread safe: feed it from one thread.
 */
class BitmapVideoEncoder(
    private val output: File,
    width: Int,
    height: Int,
    private val fps: Int = 30,
    bitrate: Int = 0,
) : AutoCloseable {
    val width = (width and 15.inv()).coerceAtLeast(16)
    val height = (height and 15.inv()).coerceAtLeast(16)

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var muxer: MediaMuxer? = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()
    private val pixels = IntArray(this.width * this.height)
    private var scaled: Bitmap? = null
    var frameCount = 0
        private set
    private var finished = false
    private var lastPtsUs = -1L

    private fun nextPtsUs() = frameCount * 1_000_000L / fps

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, this.width, this.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                if (bitrate > 0) bitrate else (this@BitmapVideoEncoder.width * this@BitmapVideoEncoder.height * fps / 6),
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            muxer?.release()
            throw e
        }
    }

    /** Adds [bitmap] (scaled to the encoder size if needed) as the next frame. */
    fun addFrame(bitmap: Bitmap, ptsUs: Long = nextPtsUs()) {
        val src = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true).also { scaled?.recycle(); scaled = it }
        }
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        addFrame(pixels, ptsUs)
    }

    /** Adds an ARGB frame of exactly [width] x [height]; [ptsUs] must increase. */
    fun addFrame(argb: IntArray, ptsUs: Long = nextPtsUs()) {
        check(!finished) { "encoder finished" }
        val index = dequeueInput()
        val image = codec.getInputImage(index) ?: error("encoder has no input image")
        val planes = image.planes
        if (!writePlanesFast(argb, planes)) writePlanes(argb, planes)
        val pts = max(ptsUs, lastPtsUs + 1)
        lastPtsUs = pts
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
        frameCount++
        drain(false)
    }

    private fun dequeueInput(): Int {
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(false)
        }
    }

    private var argbMat: Mat? = null
    private var i420Mat: Mat? = null
    private var i420 = ByteArray(0)
    private var argbBytes: ByteBuffer? = null

    /** OpenCV (NEON) colour conversion, then plane copies; false when OpenCV is unavailable. */
    private fun writePlanesFast(argb: IntArray, planes: Array<android.media.Image.Plane>): Boolean {
        if (!OpenCv.available) return false
        return try {
            // Little-endian ARGB ints are B,G,R,A bytes.
            val bytes = argbBytes ?: ByteBuffer.allocate(argb.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                .also { argbBytes = it }
            bytes.clear()
            bytes.asIntBuffer().put(argb)
            val bgra = argbMat ?: Mat(height, width, CvType.CV_8UC4).also { argbMat = it }
            bgra.put(0, 0, bytes.array())
            val dst = i420Mat ?: Mat().also { i420Mat = it }
            Imgproc.cvtColor(bgra, dst, Imgproc.COLOR_BGRA2YUV_I420)
            val size = width * height * 3 / 2
            if (i420.size != size) i420 = ByteArray(size)
            dst.get(0, 0, i420)
            copyPlane(planes[0], i420, 0, width, height)
            copyPlane(planes[1], i420, width * height, width / 2, height / 2)
            copyPlane(planes[2], i420, width * height * 5 / 4, width / 2, height / 2)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun copyPlane(plane: android.media.Image.Plane, src: ByteArray, offset: Int, w: Int, h: Int) {
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        if (ps == 1) {
            for (r in 0 until h) {
                buf.position(r * rs)
                buf.put(src, offset + r * w, w)
            }
            return
        }
        for (r in 0 until h) {
            val base = offset + r * w
            for (col in 0 until w) buf.put(r * rs + col * ps, src[base + col])
        }
    }

    private var yRowBuf = ByteArray(0)
    private var uRowBuf = ByteArray(0)
    private var vRowBuf = ByteArray(0)

    /** Row-wise bulk writes; per-pixel absolute puts were ~10x slower at 720p. */
    private fun writePlanes(argb: IntArray, planes: Array<android.media.Image.Plane>) {
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        val yBuf = y.buffer
        val uBuf = u.buffer
        val vBuf = v.buffer
        val yPix = y.pixelStride
        val uPix = u.pixelStride
        val vPix = v.pixelStride
        val yLen = (width - 1) * yPix + 1
        val cw = width / 2
        val uLen = (cw - 1) * uPix + 1
        val vLen = (cw - 1) * vPix + 1
        if (yRowBuf.size < yLen) yRowBuf = ByteArray(yLen)
        if (uRowBuf.size < uLen) uRowBuf = ByteArray(uLen)
        if (vRowBuf.size < vLen) vRowBuf = ByteArray(vLen)
        val interleaved = uPix == 2 && vPix == 2
        for (row in 0 until height) {
            val base = row * width
            val chroma = row and 1 == 0

            for (col in 0 until width) {
                val p = argb[base + col]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                yRowBuf[col * yPix] = Yuv.y(r, g, b).toByte()
                if (chroma && col and 1 == 0) {
                    uRowBuf[(col / 2) * uPix] = Yuv.u(r, g, b).toByte()
                    vRowBuf[(col / 2) * vPix] = Yuv.v(r, g, b).toByte()
                }
            }
            yBuf.position(row * y.rowStride); yBuf.put(yRowBuf, 0, yLen)
            if (chroma) {
                if (interleaved) {
                    // U and V views overlap by one byte: each write must carry the other's samples.
                    for (k in 0 until cw - 1) {
                        uRowBuf[2 * k + 1] = vRowBuf[2 * k]
                        vRowBuf[2 * k + 1] = uRowBuf[2 * k + 2]
                    }
                }
                uBuf.position((row / 2) * u.rowStride); uBuf.put(uRowBuf, 0, uLen)
                vBuf.position((row / 2) * v.rowStride); vBuf.put(vRowBuf, 0, vLen)
            }
        }
    }
    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    track = m.addTrack(codec.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && muxerStarted && !isConfig) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer?.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Flushes the encoder and closes the file. Returns false when no frame was written. */
    fun finish(): Boolean {
        if (finished) return frameCount > 0
        finished = true
        try {
            val index = dequeueInput()
            codec.queueInputBuffer(index, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            close()
        }
        return frameCount > 0 && muxerStarted
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        scaled?.recycle()
        scaled = null
        argbMat?.release(); argbMat = null
        i420Mat?.release(); i420Mat = null
        if (!muxerStarted) output.delete()
    }
}
