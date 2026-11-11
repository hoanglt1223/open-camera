/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import org.json.JSONObject

enum class ModelFormat { TFLITE, ONNX }

/** Where inference runs. QNN/NPU is deliberately absent: the app ships CPU/GPU paths only. */
enum class Accelerator { CPU, GPU, NNAPI }

/**
 * Parsed `assets/models/<id>/model.json`. [url] is null for models bundled in the APK; the
 * others are optional downloads verified against [sha256] before every first use.
 */
data class ModelSpec(
    val id: String,
    val file: String,
    val format: ModelFormat,
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
    val source: String,
    val url: String?,
    val accelerators: List<Accelerator>,
    val inputShape: IntArray,
    /** Soft limit for one inference; slower runs are treated as a failure by callers. */
    val timeoutMs: Long,
) {
    val bundled get() = url == null

    companion object {
        /** Licenses the app may ship or download (see licenses/models.md). */
        val ALLOWED_LICENSES = setOf("Apache-2.0", "MIT", "BSD-3-Clause", "BSD-2-Clause", "CC0-1.0")

        fun parse(json: String): ModelSpec {
            val o = JSONObject(json)
            val license = o.getString("license")
            require(license in ALLOWED_LICENSES) { "License $license not allowed for ${o.optString("id")}" }
            val sha = o.getString("sha256").lowercase()
            require(sha.matches(Regex("[0-9a-f]{64}"))) { "Bad sha256 for ${o.optString("id")}" }
            val accs = o.optJSONArray("accelerators")
            val shape = o.getJSONArray("inputShape")
            return ModelSpec(
                id = o.getString("id"),
                file = o.getString("file"),
                format = ModelFormat.valueOf(o.getString("format").uppercase()),
                sha256 = sha,
                sizeBytes = o.getLong("size"),
                license = license,
                source = o.getString("source"),
                url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
                accelerators = if (accs == null) {
                    listOf(Accelerator.CPU)
                } else {
                    (0 until accs.length()).map { Accelerator.valueOf(accs.getString(it)) }
                },
                inputShape = IntArray(shape.length()) { shape.getInt(it) },
                timeoutMs = o.optLong("timeoutMs", 10_000L),
            )
        }
    }

    override fun equals(other: Any?) = other is ModelSpec && other.id == id && other.sha256 == sha256
    override fun hashCode() = id.hashCode() * 31 + sha256.hashCode()
}
