/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.modes

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dev.opencamera.app.multiframe.MultiFrameSaver
import dev.opencamera.pipeline.creative.MotionPhotoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/** An NV21 frame copied out of the analysis stream. */
class RingFrame(val nv21: ByteArray, val width: Int, val height: Int, val timestampNs: Long, val rotation: Int)

/** Fixed-capacity ring of frames; the oldest is dropped (and its buffer reused) when full. */
class FrameRing(private val capacity: Int) {
    private val frames = ArrayDeque<RingFrame>()

    @Synchronized
    fun add(frame: RingFrame) {
        frames.addLast(frame)
        while (frames.size > capacity) frames.removeFirst()
    }

    /** Buffer to reuse for the next frame when the ring is full and sizes match. */
    @Synchronized
    fun recycle(size: Int): ByteArray? =
        if (frames.size >= capacity && frames.first().nv21.size == size) frames.removeFirst().nv21 else null

    @Synchronized
    fun snapshot(): List<RingFrame> = frames.toList()

    @Synchronized
    fun clear() = frames.clear()
}

/**
 * Motion photo (1.5 s before and 1 s after the shutter, H.264 in a Google Motion Photo JPEG)
 * and pre-shot buffer (frames from just before the shutter saved next to the photo). Both
 * feed from the analysis stream through [tap]; when anything fails the still photo is kept
 * as is.
 */
class MotionPhotoController(private val context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val _motionPhoto = MutableStateFlow(prefs.getBoolean(KEY_MOTION, false))
    val motionPhoto = _motionPhoto.asStateFlow()

    private val _preShot = MutableStateFlow(prefs.getBoolean(KEY_PRE_SHOT, false))
    val preShot = _preShot.asStateFlow()

    val active get() = _motionPhoto.value || _preShot.value

    private val motionRing = FrameRing(MOTION_FRAMES)
    private val preRing = FrameRing(PRE_SHOT_FRAMES)
    private var counter = 0

    fun setMotionPhoto(on: Boolean) {
        _motionPhoto.value = on
        prefs.edit { putBoolean(KEY_MOTION, on) }
        if (!on) motionRing.clear()
    }

    fun setPreShot(on: Boolean) {
        _preShot.value = on
        prefs.edit { putBoolean(KEY_PRE_SHOT, on) }
        if (!on) preRing.clear()
    }

    /** Called for every idle analysis frame (analysis thread). */
    fun tap(image: ImageProxy) {
        val n = counter++
        if (_motionPhoto.value && n % 2 == 0) {
            val f = max(1, ceil(max(image.width, image.height) / MOTION_LONG_SIDE.toFloat()).toInt())
            val w = (image.width / f) and 15.inv()
            val h = (image.height / f) and 15.inv()
            if (w >= 64 && h >= 64) motionRing.add(copy(image, f, w, h, motionRing.recycle(w * h * 3 / 2)))
        }
        if (_preShot.value && n % 3 == 0) {
            val w = image.width and 1.inv()
            val h = image.height and 1.inv()
            preRing.add(copy(image, 1, w, h, preRing.recycle(w * h * 3 / 2)))
        }
    }

    /** Stream timestamp of the newest frame: the shutter moment in the frames' own time base. */
    fun shutterTimestamp(): Long = motionRing.snapshot().lastOrNull()?.timestampNs ?: 0L

    /** Clears buffers when the stream restarts with another size or camera. */
    fun reset() {
        motionRing.clear()
        preRing.clear()
    }

    /** Saves the pre-shot frames (oldest first) as separate photos. Returns their URIs. */
    suspend fun savePreShot(timestamp: Long): List<Uri> = withContext(Dispatchers.Default) {
        if (!_preShot.value) return@withContext emptyList()
        val frames = preRing.snapshot()
        frames.mapIndexedNotNull { i, f ->
            runCatching {
                val jpeg = toJpeg(f)
                MultiFrameSaver.save(
                    context, jpeg, f.width, f.height,
                    MultiFrameSaver.Metadata(
                        timestamp = timestamp - (frames.size - i) * 100L,
                        rotationDegrees = f.rotation, captureResult = null, location = null,
                        description = "Pre-shot ${i + 1}/${frames.size}",
                    ),
                )
            }.onFailure { Log.w(TAG, "Pre-shot frame $i not saved", it) }.getOrNull()
        }
    }

    /**
     * Waits for the post-shutter part, encodes the clip and rewrites [uri] as a motion photo.
     * Returns false (photo unchanged) on any failure.
     */
    suspend fun embed(uri: Uri, shutterTimestampNs: Long): Boolean {
        if (!_motionPhoto.value) return false
        delay(POST_ROLL_MS)
        return withContext(Dispatchers.Default) {
            runCatching {
                val frames = motionRing.snapshot().filter {
                    it.timestampNs >= shutterTimestampNs - PRE_ROLL_NS && it.timestampNs <= shutterTimestampNs + POST_ROLL_MS * 1_000_000
                }
                check(frames.size >= 8) { "Only ${frames.size} frames buffered" }
                val mp4 = encode(frames)
                val jpeg = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Cannot read $uri")
                val presentationUs = ((shutterTimestampNs - frames.first().timestampNs) / 1000).coerceAtLeast(0)
                val out = MotionPhotoFormat.build(jpeg, mp4, presentationUs)
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(out) }
                    ?: error("Cannot write $uri")
                Log.i(TAG, "Motion photo: ${frames.size} frames ${frames[0].width}x${frames[0].height}, video ${mp4.size} B")
                true
            }.onFailure { Log.w(TAG, "Motion photo failed, keeping the still", it) }.getOrDefault(false)
        }
    }

    private fun encode(frames: List<RingFrame>): ByteArray {
        val w = frames[0].width
        val h = frames[0].height
        val tmp = File.createTempFile("motion", ".mp4", context.cacheDir)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(frames[0].rotation)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var next = 0
            var eos = false
            val t0 = frames[0].timestampNs
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "Encoder timed out" }
                if (!eos) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (next < frames.size) {
                            val f = frames[next++]
                            val image = checkNotNull(codec.getInputImage(i)) { "No input image" }
                            fill(image, f)
                            codec.queueInputBuffer(i, 0, w * h * 3 / 2, (f.timestampNs - t0) / 1000, 0)
                        } else {
                            codec.queueInputBuffer(i, 0, 0, (frames.last().timestampNs - t0) / 1000 + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val buf = checkNotNull(codec.getOutputBuffer(o))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            muxer.stop()
            return tmp.readBytes()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer?.release() }
            tmp.delete()
        }
    }

    private fun fill(image: android.media.Image, f: RingFrame) {
        val w = f.width
        val h = f.height
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        for (r in 0 until h) {
            yBuf.position(r * yPlane.rowStride)
            yBuf.put(f.nv21, r * w, w)
        }
        val u = image.planes[1]
        val v = image.planes[2]
        val ub = u.buffer
        val vb = v.buffer
        val base = w * h
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            val src = base + r * w + 2 * c
            vb.put(r * v.rowStride + c * v.pixelStride, f.nv21[src])
            ub.put(r * u.rowStride + c * u.pixelStride, f.nv21[src + 1])
        }
    }

    private fun toJpeg(f: RingFrame): ByteArray = ByteArrayOutputStream().also {
        YuvImage(f.nv21, ImageFormat.NV21, f.width, f.height, null).compressToJpeg(Rect(0, 0, f.width, f.height), 92, it)
    }.toByteArray()

    private fun copy(image: ImageProxy, factor: Int, w: Int, h: Int, reuse: ByteArray?): RingFrame {
        val out = reuse ?: ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yb = y.buffer
        if (factor == 1 && y.pixelStride == 1) {
            for (r in 0 until h) {
                yb.position(r * y.rowStride)
                yb.get(out, r * w, w)
            }
        } else {
            for (r in 0 until h) {
                val row = r * factor * y.rowStride
                for (c in 0 until w) out[r * w + c] = yb.get(row + c * factor * y.pixelStride)
            }
        }
        val ub = u.buffer
        val vb = v.buffer
        val base = w * h
        for (r in 0 until h / 2) {
            val ur = r * factor * u.rowStride
            val vr = r * factor * v.rowStride
            for (c in 0 until w / 2) {
                out[base + r * w + 2 * c] = vb.get(vr + c * factor * v.pixelStride)
                out[base + r * w + 2 * c + 1] = ub.get(ur + c * factor * u.pixelStride)
            }
        }
        return RingFrame(out, w, h, image.imageInfo.timestamp, image.imageInfo.rotationDegrees)
    }

    private companion object {
        const val TAG = "MotionPhoto"
        const val KEY_MOTION = "motion_photo"
        const val KEY_PRE_SHOT = "pre_shot_buffer"
        const val MOTION_LONG_SIDE = 1024
        const val MOTION_FRAMES = 48
        const val PRE_SHOT_FRAMES = 5
        const val PRE_ROLL_NS = 1_500_000_000L
        const val POST_ROLL_MS = 1_000L
    }
}
