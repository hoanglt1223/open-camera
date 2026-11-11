/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Edit stacks in the app's private storage: `<root>/<mediaId>-<hash>.json`, where the hash is
 * the first 16 bytes of the SHA-256 of the original file. Nothing is written next to the
 * photo, so no write access to other apps' files is needed. When a photo's MediaStore id
 * changes (moved, restored from trash) the stack is found again by hash and re-keyed.
 */
class EditStore(private val root: File) {
    data class Saved(val stack: EditStack, val snapshots: List<EditHistory.Snapshot>)

    fun file(ref: SourceRef) = File(root, "${ref.mediaId}-${ref.hash}.json")

    fun load(ref: SourceRef): Saved? {
        val exact = file(ref)
        if (!exact.isFile) {
            // Re-key under the new id; mask files live in a per-hash directory and stay valid.
            // Same content elsewhere (moved), else the same MediaStore id with a different hash:
            // MediaStore redacts location bytes depending on permissions, which changes the hash.
            val old = findByHash(ref.hash)
                ?: ref.mediaId.takeIf { it >= 0 }?.let { id -> root.listFiles { f -> f.name.startsWith("$id-") && f.name.endsWith(".json") }?.firstOrNull() }
                ?: return null
            if (!old.renameTo(exact)) {
                old.copyTo(exact, overwrite = true)
                old.delete()
            }
        }
        return runCatching { parse(exact.readText()) }.getOrNull()?.let { s ->
            s.copy(stack = s.stack.copy(source = ref))
        }
    }

    fun save(ref: SourceRef, stack: EditStack, snapshots: List<EditHistory.Snapshot> = emptyList()) {
        root.mkdirs()
        val json = JSONObject(StackCodec.toJson(stack.copy(source = ref)).toString())
        if (snapshots.isNotEmpty()) {
            json.put("snapshots", JSONArray().also { a ->
                snapshots.forEach { a.put(JSONObject().put("name", it.name).put("at", it.createdAt).put("stack", StackCodec.toJson(it.stack))) }
            })
        }
        val target = file(ref)
        val tmp = File(root, target.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Cannot write ${target.name}" }
        }
    }

    /** Revert: forget the stack (exports already saved stay as their own files). */
    fun delete(ref: SourceRef): Boolean {
        val f = file(ref)
        return (f.isFile && f.delete()) || findByHash(ref.hash)?.delete() == true
    }

    fun findByHash(hash: String): File? = root.listFiles { f -> f.name.endsWith("-$hash.json") }?.firstOrNull()

    /** Directory for a stack's mask and patch files. */
    fun assetDir(ref: SourceRef) = File(root, "assets-${ref.hash}").apply { mkdirs() }

    private fun parse(text: String): Saved {
        val o = JSONObject(text)
        val snaps = o.optJSONArray("snapshots")?.let { a ->
            List(a.length()) { i ->
                val s = a.getJSONObject(i)
                EditHistory.Snapshot(s.getString("name"), StackCodec.fromJson(s.getJSONObject("stack")), s.optLong("at"))
            }
        } ?: emptyList()
        return Saved(StackCodec.fromJson(o), snaps)
    }

    companion object {
        /** Hex of the first 16 bytes of the SHA-256 of [input]. */
        fun hash(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            input.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().copyOf(16).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Copies of originals kept before the user chooses to overwrite a photo, so the overwrite can
 * be undone for [RETENTION_MS].
 */
class OriginalBackups(private val root: File) {
    fun keep(hash: String, name: String, data: InputStream): File {
        root.mkdirs()
        val f = File(root, "$hash-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        data.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    fun find(hash: String): File? = root.listFiles { f -> f.name.startsWith("$hash-") }?.firstOrNull()

    fun purge(now: Long = System.currentTimeMillis()): Int =
        (root.listFiles() ?: emptyArray()).count { now - it.lastModified() > RETENTION_MS && it.delete() }

    companion object {
        const val RETENTION_MS = 30L * 24 * 3600 * 1000
    }
}
