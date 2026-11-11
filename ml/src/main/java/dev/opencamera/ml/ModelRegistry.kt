/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Model bytes ready for a backend: a mapped buffer (bundled) or a verified file (downloaded). */
sealed class ModelSource {
    class Buffer(val buffer: ByteBuffer) : ModelSource()
    class Path(val file: File) : ModelSource()
}

/**
 * Catalog of models declared under `assets/models/<id>/model.json`. Bundled models are mapped
 * straight from the APK; optional ones live in `filesDir/models/<id>/` once downloaded and are
 * hash-checked before first use in each process.
 */
class ModelRegistry(context: Context) {
    enum class State { AVAILABLE, NOT_DOWNLOADED, CORRUPT }

    private val app = context.applicationContext
    private val verified = HashSet<String>()

    val specs: Map<String, ModelSpec> by lazy { loadSpecs() }

    fun spec(id: String): ModelSpec? = specs[id]

    fun downloadedFile(spec: ModelSpec) = File(app.filesDir, "models/${spec.id}/${spec.file}")

    fun state(spec: ModelSpec): State {
        if (spec.bundled) return State.AVAILABLE
        val f = downloadedFile(spec)
        if (!f.isFile) return State.NOT_DOWNLOADED
        if (f.length() != spec.sizeBytes) return State.CORRUPT
        return State.AVAILABLE
    }

    /** Returns the model, or null when it is missing or fails its checksum (the file is removed). */
    @Synchronized
    fun open(spec: ModelSpec): ModelSource? {
        if (spec.bundled) {
            return runCatching {
                app.assets.openFd("models/${spec.id}/${spec.file}").use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.use {
                        ModelSource.Buffer(it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength))
                    }
                }
            }.onFailure { Log.e(TAG, "Bundled model ${spec.id} unreadable", it) }.getOrNull()
        }
        val f = downloadedFile(spec)
        if (state(spec) != State.AVAILABLE) return null
        val key = "${f.path}:${f.length()}:${f.lastModified()}"
        if (key !in verified) {
            val actual = f.inputStream().use(::sha256)
            if (actual != spec.sha256) {
                Log.e(TAG, "Checksum mismatch for ${spec.id}: $actual, removing")
                f.delete()
                return null
            }
            verified += key
        }
        return ModelSource.Path(f)
    }

    private fun loadSpecs(): Map<String, ModelSpec> {
        val ids = runCatching { app.assets.list("models")?.toList() }.getOrNull().orEmpty()
        return ids.mapNotNull { id ->
            runCatching {
                ModelSpec.parse(app.assets.open("models/$id/model.json").bufferedReader().use { it.readText() })
            }.onFailure { Log.e(TAG, "Bad model.json for $id", it) }.getOrNull()
        }.associateBy { it.id }
    }

    companion object {
        private const val TAG = "ModelRegistry"

        @Volatile
        private var instance: ModelRegistry? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: ModelRegistry(context).also { instance = it }
        }

        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
