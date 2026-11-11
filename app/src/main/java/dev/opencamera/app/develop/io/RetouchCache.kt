/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.io

import android.graphics.Bitmap
import android.util.Log
import dev.opencamera.app.develop.model.ToolStep
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Proxy-size results of retouch stacks, so reopening an edited photo shows the result without
 * running the models again. Raw ARGB with a small header: writing a PNG of a 5 MP proxy takes
 * longer than replaying most tools. Least recently used entries go above [maxBytes].
 * Every failure is a miss; the caller then replays the stack.
 */
class RetouchCache(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    fun get(key: String, width: Int, height: Int): Bitmap? {
        val f = File(dir, "$key.argb")
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered(1 shl 16)).use { input ->
                val magic = input.readInt()
                val w = input.readInt()
                val h = input.readInt()
                if (!headerMatches(magic, w, h, width, height, f.length())) return@use null
                val bytes = ByteArray(w * h * 4)
                input.readFully(bytes)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                    copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                }
            }
        }.onFailure { Log.w(TAG, "Dropping unreadable cache entry", it) }.getOrNull()
            .also { if (it == null) f.delete() else f.setLastModified(System.currentTimeMillis()) }
    }

    fun put(key: String, bitmap: Bitmap) {
        runCatching {
            if (bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.isRecycled) return
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            val buf = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buf)
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(bitmap.width)
                out.writeInt(bitmap.height)
                out.write(buf.array(), 0, bitmap.width * bitmap.height * 4)
            }
            if (!tmp.renameTo(File(dir, "$key.argb"))) tmp.delete()
            trim()
        }.onFailure { Log.w(TAG, "Cannot cache retouch result", it) }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".argb") } ?: return
        evictions(files.map { Entry(it.name, it.length(), it.lastModified()) }, maxBytes)
            .forEach { File(dir, it).delete() }
    }

    data class Entry(val name: String, val bytes: Long, val lastUsed: Long)

    companion object {
        private const val TAG = "RetouchCache"
        private const val MAGIC = 0x52435431 // "RCT1"
        private const val HEADER = 12L
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024

        /** Key of the result of [steps] on the photo with [sourceHash] at a [width]x[height] proxy. */
        fun key(sourceHash: String, width: Int, height: Int, steps: List<ToolStep>): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("$sourceHash|${width}x$height".toByteArray())
            for (s in steps) {
                md.update(0)
                md.update("${s.toolId}\u0000${s.params}\u0000${s.files.joinToString("\u0001")}\u0000${s.enabled}".toByteArray())
            }
            return md.digest().take(16).joinToString("") { "%02x".format(it) }
        }

        fun headerMatches(magic: Int, w: Int, h: Int, width: Int, height: Int, fileLength: Long) =
            magic == MAGIC && w == width && h == height && fileLength == HEADER + w.toLong() * h * 4

        /** Names to delete so the rest fits [maxBytes], least recently used first. */
        fun evictions(entries: List<Entry>, maxBytes: Long): List<String> {
            var total = entries.sumOf { it.bytes }
            val out = ArrayList<String>()
            for (e in entries.sortedBy { it.lastUsed }) {
                if (total <= maxBytes) break
                total -= e.bytes
                out += e.name
            }
            return out
        }
    }
}
