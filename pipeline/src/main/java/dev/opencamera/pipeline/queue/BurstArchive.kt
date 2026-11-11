/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.queue

import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * A captured burst on disk, so its merge can resume after process death. Frames are NV21,
 * deflate-compressed (lossless, fast level); `meta.properties` is written last and marks the
 * archive complete.
 */
object BurstArchive {
    private const val META = "meta.properties"
    private const val KEY_FRAMES = "frames"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"

    class Archive(val dir: File, val width: Int, val height: Int, val frameCount: Int, val meta: Map<String, String>)

    /**
     * Writes [frameCount] frames from [frame] (called sequentially, may return null to stop)
     * compressed on [threads] threads, then the metadata. Returns the number of frames written.
     */
    fun write(
        dir: File,
        width: Int,
        height: Int,
        frameCount: Int,
        meta: Map<String, String>,
        threads: Int = 4,
        frame: (Int) -> ByteArray?,
    ): Int {
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
        var written = 0
        try {
            val jobs = ArrayList<java.util.concurrent.Future<*>>()
            for (i in 0 until frameCount) {
                val data = frame(i) ?: break
                require(data.size >= width * height * 3 / 2) { "Frame $i too small" }
                jobs += pool.submit {
                    val tmp = File(dir, "frame_$i.tmp")
                    DeflaterOutputStream(tmp.outputStream().buffered(1 shl 16), Deflater(Deflater.BEST_SPEED), 1 shl 16)
                        .use { it.write(data, 0, width * height * 3 / 2) }
                    if (!tmp.renameTo(frameFile(dir, i))) throw IOException("Cannot store frame $i")
                }
                written++
            }
            jobs.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        val props = Properties()
        meta.forEach { (k, v) -> props.setProperty(k, v) }
        props.setProperty(KEY_FRAMES, written.toString())
        props.setProperty(KEY_WIDTH, width.toString())
        props.setProperty(KEY_HEIGHT, height.toString())
        val tmp = File(dir, "$META.tmp")
        tmp.outputStream().use { props.store(it, null) }
        if (!tmp.renameTo(File(dir, META))) throw IOException("Cannot commit $dir")
        return written
    }

    /** The archive in [dir], or null when it is missing or incomplete. */
    fun open(dir: File): Archive? {
        val file = File(dir, META)
        if (!file.isFile) return null
        val props = Properties()
        runCatching { file.inputStream().use { props.load(it) } }.getOrElse { return null }
        val frames = props.getProperty(KEY_FRAMES)?.toIntOrNull() ?: return null
        val width = props.getProperty(KEY_WIDTH)?.toIntOrNull() ?: return null
        val height = props.getProperty(KEY_HEIGHT)?.toIntOrNull() ?: return null
        if (frames < 1 || width < 2 || height < 2 || (0 until frames).any { !frameFile(dir, it).isFile }) return null
        val meta = props.stringPropertyNames().associateWith { props.getProperty(it) }
        return Archive(dir, width, height, frames, meta)
    }

    fun readFrame(archive: Archive, index: Int): ByteArray {
        val out = ByteArray(archive.width * archive.height * 3 / 2)
        InflaterInputStream(frameFile(archive.dir, index).inputStream().buffered(1 shl 16), java.util.zip.Inflater(), 1 shl 16)
            .use { input ->
                var off = 0
                while (off < out.size) {
                    val n = input.read(out, off, out.size - off)
                    if (n < 0) throw IOException("Frame $index truncated")
                    off += n
                }
            }
        return out
    }

    fun delete(dir: File) {
        dir.deleteRecursively()
    }

    private fun frameFile(dir: File, index: Int) = File(dir, "frame_$index.nv21z")
}
