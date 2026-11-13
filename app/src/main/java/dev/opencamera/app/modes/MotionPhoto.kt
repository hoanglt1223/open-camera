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
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.ml.ML_RUNTIME_INCLUDED
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.pipeline.creative.BestTake
import dev.opencamera.pipeline.creative.MotionFailure
import dev.opencamera.pipeline.creative.MotionPhotoFormat
import dev.opencamera.pipeline.creative.MotionPhotoPolicy
import dev.opencamera.app.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
class FrameRing(private val maxFrames: Int, private val budgetBytes: Long = Long.MAX_VALUE) {
    private val frames = ArrayDeque<RingFrame>()

    private fun capacity(frameBytes: Int) = MotionPhotoPolicy.ringCapacity(frameBytes, maxFrames, budgetBytes)

    @Synchronized
    fun add(frame: RingFrame) {
        frames.addLast(frame)
        val cap = capacity(frame.nv21.size)
        while (frames.size > cap) frames.removeFirst()
    }

    /** Buffer to reuse for the next frame when the ring is full and sizes match. */
    private var holds = 0

    /** While held, evicted buffers are not reused, so a snapshot's frames stay intact. */
    @Synchronized
    fun hold() { holds++ }

    @Synchronized
    fun release() { holds = (holds - 1).coerceAtLeast(0) }

    @Synchronized
    fun recycle(size: Int): ByteArray? =
        if (holds == 0 && frames.isNotEmpty() && frames.size >= capacity(size) && frames.first().nv21.size == size) frames.removeFirst().nv21 else null

    @get:Synchronized
    val bytes: Long get() = frames.sumOf { it.nv21.size.toLong() }

    @get:Synchronized
    val size: Int get() = frames.size

    @Synchronized
    fun snapshot(): List<RingFrame> = frames.toList()

    @Synchronized
    fun clear() = frames.clear()
}

/**
 * Motion photo (1.5 s before and 1.5 s after the shutter, H.264 in a Google Motion Photo JPEG)
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

    private val motionRing = FrameRing(MOTION_FRAMES, MOTION_BUDGET_BYTES)
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    @Volatile
    private var memoryLow = false

    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    /** One-time notices (string resources) when a motion photo fell back to the plain still. */
    val notices = _notices.asSharedFlow()

    /** A sharper take than the saved still, found in the clip of the photo at [uri]. */
    class BestTakeOffer(val uri: Uri, val gain: Double)

    private val _bestTakeOffers = MutableSharedFlow<BestTakeOffer>(extraBufferCapacity = 1)
    val bestTakeOffers = _bestTakeOffers.asSharedFlow()

    private class PendingTake(val uri: Uri, val frame: RingFrame, val presentationUs: Long)

    @Volatile
    private var pendingTake: PendingTake? = null
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
        if (_motionPhoto.value && n % MEMORY_CHECK_EVERY == 0) {
            memoryLow = isMemoryLow()
            if (memoryLow) motionRing.clear()
        }
        if (_motionPhoto.value && !memoryLow && n % 2 == 0) {
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
     * On any failure the still is left as is, the reason is logged and a notice is shown once
     * per reason. Returns true when the clip was embedded.
     */
    suspend fun embed(uri: Uri, shutterTimestampNs: Long): Boolean {
        if (!_motionPhoto.value) return false
        pendingTake = null
        if (shutterTimestampNs != 0L && !memoryLow) delay(POST_ROLL_MS)
        motionRing.hold()
        try {
            return embedHeld(uri, shutterTimestampNs)
        } finally {
            motionRing.release()
        }
    }

    private suspend fun embedHeld(uri: Uri, shutterTimestampNs: Long): Boolean {
        return withContext(Dispatchers.Default) {
            val all = motionRing.snapshot()
            val window = MotionPhotoPolicy.window(all.map { it.timestampNs }, shutterTimestampNs, PRE_ROLL_NS, POST_ROLL_MS * 1_000_000)
            val frames = window.map { all[it] }
            MotionPhotoPolicy.precheck(shutterTimestampNs, frames.size, memoryLow || isMemoryLow())?.let {
                fail(it, "${frames.size} frames in the window, ring ${motionRing.size} frames ${motionRing.bytes / 1024} KiB")
                return@withContext false
            }
            val t0 = SystemClock.elapsedRealtime()
            val mp4 = try {
                encode(frames)
            } catch (e: Exception) {
                fail(MotionFailure.ENCODE, e.toString())
                return@withContext false
            }
            val presentationUs = ((shutterTimestampNs - frames.first().timestampNs) / 1000).coerceAtLeast(0)
            try {
                rewrite(uri) { jpeg -> MotionPhotoFormat.build(jpeg, mp4, presentationUs) }
            } catch (e: Exception) {
                fail(MotionFailure.WRITE, e.toString())
                return@withContext false
            }
            Log.i(TAG, "Motion photo: ${frames.size} frames ${frames[0].width}x${frames[0].height}, video ${mp4.size} B, " +
                "ring ${motionRing.bytes / 1024} KiB, encode+write ${SystemClock.elapsedRealtime() - t0} ms")
            runCatching { findBestTake(uri, frames, shutterTimestampNs) }
                .onFailure { Log.w(TAG, "Best take search failed", it) }
            true
        }
    }

    /** Replaces the still of the motion photo at [uri] with the offered take. False if there is none or it failed. */
    suspend fun useBestTake(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val take = pendingTake?.takeIf { it.uri == uri } ?: return@withContext false
        runCatching {
            rewrite(uri) { file ->
                val video = MotionPhotoFormat.extractVideo(file) ?: error("Not a motion photo")
                MotionPhotoFormat.build(stillFromFrame(take.frame, file), video, take.presentationUs)
            }
            pendingTake = null
            Log.i(TAG, "Best take applied to $uri")
            true
        }.onFailure {
            Log.w(TAG, "Best take not applied, keeping the still", it)
            _notices.tryEmit(R.string.motion_best_take_failed)
        }.getOrDefault(false)
    }

    private fun findBestTake(uri: Uri, frames: List<RingFrame>, shutterTimestampNs: Long) {
        val t0 = SystemClock.elapsedRealtime()
        val shutter = MotionPhotoPolicy.closest(frames.map { it.timestampNs }, shutterTimestampNs)
        val sharp = DoubleArray(frames.size) { BestTake.sharpness(frames[it].nv21, frames[it].width, frames[it].height) }
        val indices = (BestTake.sharpest(sharp, FACE_CANDIDATES) + shutter).distinct()
        val faces = faceMesh()
        val candidates = indices.map { i -> BestTake.Candidate(i, sharp[i], faces?.let { eyes(it, frames[i]) }) }
        val best = BestTake.pick(candidates, shutter)
        Log.i(TAG, "Best take: shutter #$shutter ${"%.0f".format(sharp[shutter])}, best #${best?.index} " +
            "${best?.sharpness?.let { "%.0f".format(it) }}, faces ${faces != null}, ${SystemClock.elapsedRealtime() - t0} ms")
        best ?: return
        val f = frames[best.index]
        val copy = RingFrame(f.nv21.copyOf(), f.width, f.height, f.timestampNs, f.rotation)
        pendingTake = PendingTake(uri, copy, ((f.timestampNs - frames.first().timestampNs) / 1000).coerceAtLeast(0))
        val shutterScore = BestTake.score(candidates.first { it.index == shutter })
        _bestTakeOffers.tryEmit(BestTakeOffer(uri, if (shutterScore > 0) BestTake.score(best) / shutterScore else 0.0))
    }

    /** Face landmarks only when the downloaded detector models are already present. */
    private fun faceMesh(): FaceMesh? {
        if (!ML_RUNTIME_INCLUDED) return null
        return runCatching { FaceMesh(MlRuntime.get(context)).takeIf { it.available } }.getOrNull()
    }

    /** Least-open eye over the faces in [f], or null without faces. */
    private fun eyes(faces: FaceMesh, f: RingFrame): Float? {
        val bitmap = upright(f)
        try {
            val found = faces.detect(bitmap, 4).orEmpty().filter { it.landmarks.size > 400 }
            if (found.isEmpty()) return null
            return found.minOf { face ->
                minOf(earOf(face.landmarks, LEFT_EYE), earOf(face.landmarks, RIGHT_EYE)).let(BestTake::eyeOpenness)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun earOf(lm: List<PointF>, eye: IntArray): Float {
        fun d(a: Int, b: Int) = kotlin.math.hypot(lm[a].x - lm[b].x, lm[a].y - lm[b].y)
        val width = d(eye[0], eye[1])
        return if (width <= 0f) 1f else d(eye[2], eye[3]) / width
    }

    private fun upright(f: RingFrame): Bitmap {
        val jpeg = toJpeg(f)
        val raw = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "Frame decode failed" }
        if (f.rotation == 0) return raw
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(f.rotation.toFloat()) }, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /** The take as an upright JPEG carrying the original's date, camera and location tags. */
    private fun stillFromFrame(f: RingFrame, original: ByteArray): ByteArray {
        val bitmap = upright(f)
        val tmp = File.createTempFile("take", ".jpg", context.cacheDir)
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            val src = ExifInterface(original.inputStream())
            val dst = ExifInterface(tmp)
            for (tag in CARRIED_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.saveAttributes()
            return tmp.readBytes()
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
    }

    private fun rewrite(uri: Uri, transform: (ByteArray) -> ByteArray) {
        val file = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read $uri")
        val out = transform(file)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(out) } ?: error("Cannot write $uri")
    }

    private fun fail(reason: MotionFailure, detail: String) {
        Log.w(TAG, "Motion photo fell back to the still ($reason): $detail")
        val key = "motion_notice_${reason.name}"
        if (!prefs.getBoolean(key, false)) {
            prefs.edit { putBoolean(key, true) }
            _notices.tryEmit(
                when (reason) {
                    MotionFailure.LOW_MEMORY -> R.string.motion_photo_fallback_memory
                    MotionFailure.NO_STREAM, MotionFailure.TOO_FEW_FRAMES -> R.string.motion_photo_fallback_stream
                    MotionFailure.ENCODE, MotionFailure.WRITE -> R.string.motion_photo_fallback_encode
                }
            )
        }
    }

    private fun isMemoryLow(): Boolean {
        val rt = Runtime.getRuntime()
        val heapFree = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val info = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        return heapFree < MIN_FREE_HEAP_BYTES || info.lowMemory
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
            val ys = factor * y.pixelStride
            for (r in 0 until h) {
                val row = readRow(yb, r * factor * y.rowStride, (w - 1) * ys + 1)
                for (c in 0 until w) out[r * w + c] = row[c * ys]
            }
        }
        // Rows are read in bulk and decimated from a heap array; ByteBuffer.get(index) per pixel
        // on a direct buffer cost most of a core.
        val ub = u.buffer
        val vb = v.buffer
        val base = w * h
        val us = factor * u.pixelStride
        val vs = factor * v.pixelStride
        for (r in 0 until h / 2) {
            val vRow = readRow(vb, r * factor * v.rowStride, (w / 2 - 1) * vs + 1)
            for (c in 0 until w / 2) out[base + r * w + 2 * c] = vRow[c * vs]
            val uRow = readRow(ub, r * factor * u.rowStride, (w / 2 - 1) * us + 1)
            for (c in 0 until w / 2) out[base + r * w + 2 * c + 1] = uRow[c * us]
        }
        return RingFrame(out, w, h, image.imageInfo.timestamp, image.imageInfo.rotationDegrees)
    }

    private var rowBuffer = ByteArray(0)

    /** Copies [len] bytes at [offset] (clamped to the buffer) into a reused array. */
    private fun readRow(buffer: java.nio.ByteBuffer, offset: Int, len: Int): ByteArray {
        if (rowBuffer.size < len) rowBuffer = ByteArray(len)
        val n = minOf(len, buffer.limit() - offset).coerceAtLeast(0)
        buffer.position(offset)
        buffer.get(rowBuffer, 0, n)
        return rowBuffer
    }
    private companion object {
        const val TAG = "MotionPhoto"
        const val KEY_MOTION = "motion_photo"
        const val KEY_PRE_SHOT = "pre_shot_buffer"
        const val MOTION_LONG_SIDE = 768
        const val MOTION_FRAMES = 48
        const val MOTION_BUDGET_BYTES = 32L shl 20
        const val MIN_FREE_HEAP_BYTES = 48L shl 20
        const val MEMORY_CHECK_EVERY = 30
        const val FACE_CANDIDATES = 3
        val LEFT_EYE = intArrayOf(33, 133, 159, 145)
        val RIGHT_EYE = intArrayOf(362, 263, 386, 374)
        val CARRIED_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        )
        const val PRE_SHOT_FRAMES = 5
        const val PRE_ROLL_NS = 1_500_000_000L
        const val POST_ROLL_MS = 1_500L
    }
}
