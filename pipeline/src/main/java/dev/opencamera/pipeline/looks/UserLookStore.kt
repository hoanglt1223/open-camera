/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import org.json.JSONObject
import java.io.File

/**
 * User looks (colour match results and imported `.cube` files), stored next to the built-in
 * ones in the same format: `<root>/<id>/look.json` + `lut.cube`. Ids start with [PREFIX] so
 * they never clash with built-in looks.
 */
class UserLookStore(private val root: File) {
    fun list(): List<LookDescriptor> = (root.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.name.startsWith(PREFIX) }
        .sortedBy { it.lastModified() }
        .mapNotNull { dir ->
            runCatching { LookDescriptor.parse(File(dir, LOOK_JSON).readText()) }.getOrNull()
        }

    fun lut(descriptor: LookDescriptor): Lut3d =
        CubeParser.parse(File(File(root, descriptor.id), descriptor.lutFile).readText())

    /** Saves a new look and returns its descriptor. Writes to a temp dir first, then renames. */
    fun save(name: String, description: String, lut: Lut3d, intensity: Float = 1f): LookDescriptor {
        val cleanName = name.trim().ifEmpty { "Look" }.take(40)
        val id = PREFIX + System.currentTimeMillis().toString(36) + (0..999).random()
        val json = JSONObject()
            .put("id", id)
            .put("name", cleanName)
            .put("description", description)
            .put("lut", LUT_FILE)
            .put("intensity", intensity.coerceIn(0f, 1f).toDouble())
        root.mkdirs()
        val tmp = File(root, ".tmp-$id").apply { deleteRecursively(); mkdirs() }
        File(tmp, LUT_FILE).writeText(CubeWriter.write(lut, cleanName))
        File(tmp, LOOK_JSON).writeText(json.toString(2))
        val dir = File(root, id)
        check(tmp.renameTo(dir)) { "Cannot store look $id" }
        return LookDescriptor.parse(json.toString())
    }

    fun delete(id: String): Boolean = id.startsWith(PREFIX) && File(root, id).deleteRecursively()

    companion object {
        const val PREFIX = "user-"
        private const val LOOK_JSON = "look.json"
        private const val LUT_FILE = "lut.cube"
    }
}
