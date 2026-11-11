/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Slow motion without a high-speed session: a 60 or 120 fps clip is re-timed so it plays at 30
 * fps, without re-encoding. Audio is dropped (it would play at the wrong pitch).
 */
object VideoRetimer {
    /** Stretches video timestamps by [factor] (2 = half speed). Returns false if no video track. */
    fun retime(input: FileDescriptor, output: File, factor: Int): Boolean {
        require(factor >= 1)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            rotationOf(input)?.let { muxer.setOrientationHint(it) }
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                8 shl 20
            }
            val buffer = ByteBuffer.allocateDirect(size)
            val info = MediaCodec.BufferInfo()
            var first = -1L
            while (true) {
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                val t = extractor.sampleTime
                if (first < 0) first = t
                info.set(0, n, (t - first) * factor, extractor.sampleFlags.toCodecFlags())
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            return true
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun Int.toCodecFlags(): Int {
        var flags = 0
        if (this and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        return flags
    }

    private fun rotationOf(fd: FileDescriptor): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(fd)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Playback factor for a capture rate shown at 30 fps, or 0 when it is no slow motion. */
    fun factorFor(captureFps: Int) = if (captureFps >= 60) captureFps / 30 else 0
}
