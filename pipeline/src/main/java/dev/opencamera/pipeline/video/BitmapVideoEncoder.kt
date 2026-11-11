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
import java.io.File
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
        writePlanes(argb, planes)
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

    private fun writePlanes(argb: IntArray, planes: Array<android.media.Image.Plane>) {
        val yPlane = planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val cRow = uPlane.rowStride
        val cPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        for (row in 0 until height) {
            for (col in 0 until width) {
                val p = argb[row * width + col]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                yBuf.put(row * yRow + col * yPix, Yuv.y(r, g, b).toByte())
                if (row and 1 == 0 && col and 1 == 0) {
                    uBuf.put((row / 2) * cRow + (col / 2) * cPix, Yuv.u(r, g, b).toByte())
                    vBuf.put((row / 2) * vRow + (col / 2) * vPix, Yuv.v(r, g, b).toByte())
                }
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
        if (!muxerStarted) output.delete()
    }
}
