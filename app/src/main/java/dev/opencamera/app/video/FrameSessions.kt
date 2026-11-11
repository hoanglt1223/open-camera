/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dev.opencamera.app.R
import dev.opencamera.capture.video.FrameCorrection
import dev.opencamera.capture.video.GyroPath
import dev.opencamera.capture.video.LiveStabilizer
import dev.opencamera.capture.video.SweepGuide
import dev.opencamera.pipeline.panorama.PanoramaProcessor
import dev.opencamera.pipeline.video.BitmapVideoEncoder
import dev.opencamera.pipeline.video.FrameAccumulator
import dev.opencamera.pipeline.video.TimeFreezeCompositor
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A capture built from analysis frames. All calls come from the single analysis thread; frames
 * are upright ARGB bitmaps the session may keep (it recycles what it does not keep).
 */
abstract class FrameSession {
    /** Status line for the UI, e.g. frame count. */
    @Volatile
    var status: String = ""
        protected set

    /** Set when the session has what it needs and should be finished. */
    @Volatile
    var complete = false
        protected set

    /** Cheap gate checked before a frame is converted to a bitmap. */
    open fun wantsFrame(timestampNs: Long) = true

    abstract fun onFrame(frame: Bitmap, timestampNs: Long)

    /** Writes the result; returns a user message (saved or why not). */
    abstract fun finish(context: Context): String

    open fun cancel() = Unit
}

private fun scaleToLongSide(src: Bitmap, longSide: Int): Bitmap {
    val s = longSide.toFloat() / max(src.width, src.height)
    if (s >= 1f) return src
    return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
        .also { src.recycle() }
}

/** Encoder-backed sessions: frames become an MP4 saved to the gallery on finish. */
abstract class EncodingSession(
    private val cacheDir: File,
    private val prefix: String,
    protected val longSide: Int,
    private val fps: Int = 30,
) : FrameSession() {
    private var encoder: BitmapVideoEncoder? = null
    private val file = File(cacheDir, "$prefix-${System.nanoTime()}.mp4")
    protected val frames get() = encoder?.frameCount ?: 0

    protected fun encoder(width: Int, height: Int): BitmapVideoEncoder =
        encoder ?: BitmapVideoEncoder(file, width, height, fps).also { encoder = it }

    override fun finish(context: Context): String {
        val enc = encoder ?: return context.getString(R.string.video_modes_no_frames)
        val ok = runCatching { enc.finish() }.getOrDefault(false)
        if (!ok) return context.getString(R.string.video_modes_no_frames).also { file.delete() }
        return try {
            MediaSaver.saveVideo(context, file, prefix)
            context.getString(R.string.video_modes_saved_video, enc.frameCount)
        } finally {
            file.delete()
        }
    }

    override fun cancel() {
        encoder?.close()
        file.delete()
    }
}

/** Time-lapse: one frame every [intervalMs], played back at 30 fps. */
class TimeLapseSession(cacheDir: File, private val intervalMs: Long) :
    EncodingSession(cacheDir, "TIMELAPSE", 1920) {
    private var last = Long.MIN_VALUE

    override fun wantsFrame(timestampNs: Long) =
        last == Long.MIN_VALUE || timestampNs - last >= intervalMs * 1_000_000

    override fun onFrame(frame: Bitmap, timestampNs: Long) {
        last = timestampNs
        val f = scaleToLongSide(frame, longSide)
        encoder(f.width, f.height).addFrame(f)
        f.recycle()
        status = "$frames · ${"%.1f".format(frames / 30f)} s"
    }
}

/**
 * Hyperlapse: keeps one frame per [speed] source frames (~33 ms each) and counters hand shake
 * with the gyro path (causal low-pass, 10% crop).
 */
class HyperlapseSession(
    cacheDir: File,
    private val speed: Int,
    private val gyro: GyroPath,
    private val focalOverSensor: Float,
) : EncodingSession(cacheDir, "HYPERLAPSE", 1920) {
    private val stabilizer = LiveStabilizer()
    private var last = Long.MIN_VALUE
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var out: Bitmap? = null

    override fun wantsFrame(timestampNs: Long) =
        last == Long.MIN_VALUE || timestampNs - last >= speed * 33_333_333L

    override fun onFrame(frame: Bitmap, timestampNs: Long) {
        last = timestampNs
        val f = scaleToLongSide(frame, longSide)
        val w = f.width
        val h = f.height
        val target = out ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out = it }
        val correction = stabilizer.correction(gyro.angleAt(timestampNs))
        val c = FrameCorrection.from(correction, focalOverSensor * max(w, h), frameLongSidePx = max(w, h))
        Canvas(target).drawBitmap(f, stabilizationMatrix(c, w, h), paint)
        f.recycle()
        encoder(w, h).addFrame(target)
        status = "$frames · ${"%.1f".format(frames / 30f)} s"
    }

    override fun finish(context: Context): String = super.finish(context).also { out?.recycle() }

    companion object {
        fun stabilizationMatrix(c: FrameCorrection, w: Int, h: Int) = Matrix().apply {
            postTranslate(c.dxPx, c.dyPx)
            postRotate(c.rollDeg, w / 2f, h / 2f)
            postScale(FrameCorrection.CROP_SCALE, FrameCorrection.CROP_SCALE, w / 2f, h / 2f)
        }
    }
}

/** Time freeze: a line sweeps across in [durationMs]; everything behind it stops moving. */
class TimeFreezeSession(cacheDir: File, private val durationMs: Long) :
    EncodingSession(cacheDir, "TIMEFREEZE", 1280) {
    private var start = -1L
    private var compositor: TimeFreezeCompositor? = null
    private var pixels = IntArray(0)

    /** Line position 0..1 for the overlay. */
    @Volatile
    var progress = 0f
        private set

    override fun onFrame(frame: Bitmap, timestampNs: Long) {
        if (start < 0) start = timestampNs
        val f = scaleToLongSide(frame, longSide)
        val enc = encoder(f.width, f.height)
        val w = enc.width
        val h = enc.height
        val scaled = if (f.width != w || f.height != h) Bitmap.createScaledBitmap(f, w, h, true) else f
        if (pixels.size != w * h) pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== f) scaled.recycle()
        f.recycle()
        progress = ((timestampNs - start) / 1e6f / durationMs).coerceIn(0f, 1f)
        val comp = compositor ?: TimeFreezeCompositor(w, h).also { compositor = it }
        enc.addFrame(comp.compose(pixels, progress), (timestampNs - start) / 1000)
        if (progress >= 1f) complete = true
        status = "${(progress * 100).roundToInt()}%"
    }
}

/** Slow shutter by accumulating [durationMs] of frames into one photo. */
class SlowShutterSession(private val mode: FrameAccumulator.Mode, private val durationMs: Long) : FrameSession() {
    private var start = -1L
    private var accumulator: FrameAccumulator? = null
    private var pixels = IntArray(0)

    override fun onFrame(frame: Bitmap, timestampNs: Long) {
        if (start < 0) start = timestampNs
        val f = scaleToLongSide(frame, 2048)
        val acc = accumulator ?: FrameAccumulator(f.width, f.height, mode).also { accumulator = it }
        if (f.width == acc.width && f.height == acc.height) {
            if (pixels.size != acc.width * acc.height) pixels = IntArray(acc.width * acc.height)
            f.getPixels(pixels, 0, acc.width, 0, 0, acc.width, acc.height)
            acc.add(pixels)
        }
        f.recycle()
        val elapsed = (timestampNs - start) / 1_000_000
        if (elapsed >= durationMs) complete = true
        status = "${acc.frames} · ${elapsed / 1000}.${(elapsed % 1000) / 100} s"
    }

    override fun finish(context: Context): String {
        val acc = accumulator ?: return context.getString(R.string.video_modes_no_frames)
        if (acc.frames == 0) return context.getString(R.string.video_modes_no_frames)
        val bmp = Bitmap.createBitmap(acc.result(), acc.width, acc.height, Bitmap.Config.ARGB_8888)
        MediaSaver.saveJpeg(context, bmp, "SLOWSHUTTER")
        bmp.recycle()
        return context.getString(R.string.video_modes_saved_photo)
    }
}

/** Panorama: the sweep guide picks frames by gyro yaw; they are stitched on finish. */
class PanoramaSession(
    private val guide: SweepGuide,
    private val focalOverSensor: Float,
    private val maxFrames: Int = 14,
) : FrameSession() {
    private val frames = mutableListOf<PanoramaProcessor.Frame>()

    override fun wantsFrame(timestampNs: Long) = guide.advice() == SweepGuide.Advice.CAPTURE

    override fun onFrame(frame: Bitmap, timestampNs: Long) {
        if (guide.advice() != SweepGuide.Advice.CAPTURE) {
            frame.recycle()
            return
        }
        frames += PanoramaProcessor.Frame(scaleToLongSide(frame, 1440), guide.yawDeg)
        guide.onCaptured()
        if (frames.size >= maxFrames || guide.advice() == SweepGuide.Advice.DONE) complete = true
        status = "${frames.size}"
    }

    override fun finish(context: Context): String {
        try {
            if (frames.size < 2) return context.getString(R.string.video_modes_panorama_too_short)
            val longSide = max(frames[0].bitmap.width, frames[0].bitmap.height)
            val pano = PanoramaProcessor.stitch(frames, focalOverSensor * longSide)
                ?: return context.getString(R.string.video_modes_panorama_failed)
            MediaSaver.saveJpeg(context, pano, "PANO")
            pano.recycle()
            return context.getString(R.string.video_modes_saved_photo)
        } finally {
            cancel()
        }
    }

    override fun cancel() {
        frames.forEach { it.bitmap.recycle() }
        frames.clear()
    }
}

/** Upright copy of an analysis bitmap. */
fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true).also { if (it !== this) recycle() }
}
