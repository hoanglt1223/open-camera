/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches optional models from the project's release page. The file is written to a temporary
 * name, hashed while streaming and only renamed into place when size and SHA-256 match, so a
 * partial or tampered download is never loaded.
 */
class ModelDownloader(
    private val targetFor: (ModelSpec) -> File,
    private val open: (URL) -> InputStream = ::httpOpen,
) {
    constructor(registry: ModelRegistry) : this(registry::downloadedFile)

    fun interface Progress {
        fun onProgress(bytes: Long, total: Long)
    }

    /** Returns normally once the model is in place; throws [IOException] otherwise. */
    fun download(spec: ModelSpec, progress: Progress? = null) {
        val url = spec.url ?: return
        val target = targetFor(spec)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.part")
        val md = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            open(URL(url)).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > spec.sizeBytes) throw IOException("${spec.id}: larger than declared")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        progress?.onProgress(written, spec.sizeBytes)
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (written != spec.sizeBytes || hash != spec.sha256) {
                throw IOException("${spec.id}: checksum mismatch ($written bytes, $hash)")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Cannot move ${spec.id} into place")
        } finally {
            tmp.delete()
        }
    }

    fun delete(spec: ModelSpec) {
        if (!spec.bundled) targetFor(spec).delete()
    }

    companion object {
        private const val TIMEOUT_MS = 30_000

        fun httpOpen(url: URL): InputStream {
            require(url.protocol == "https") { "Models are only downloaded over HTTPS" }
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) {
                c.disconnect()
                throw IOException("HTTP ${c.responseCode} for $url")
            }
            return c.inputStream
        }
    }
}
