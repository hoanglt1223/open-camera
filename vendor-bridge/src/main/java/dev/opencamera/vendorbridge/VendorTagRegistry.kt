/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.vendorbridge

import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log

/**
 * Vendor request tags from the device profile (`"vendorTags": {"<tag name>": "<type>:<value>"}`).
 * Types: `i32`, `i64`, `u8` (byte), `f32`, `f64`, `bool`, `i32[]` (comma separated).
 * A tag is written only when the profile is `verified`, the camera lists the tag among its
 * request keys and it has not failed this session.
 */
class VendorTagRegistry {
    data class Tag(val name: String, val type: Class<*>, val value: Any)

    private val disabled = mutableSetOf<String>()

    @Synchronized
    fun disable(name: String, reason: String) {
        if (disabled.add(name)) Log.w(LOG_TAG, "Vendor tag $name disabled for this session: $reason")
    }

    @Synchronized
    fun isDisabled(name: String) = name in disabled

    /** Tags to write on a camera whose request key names are [availableRequestKeys]. */
    fun select(
        profileTags: Map<String, String>,
        verified: Boolean,
        availableRequestKeys: Set<String>,
    ): List<Tag> {
        if (profileTags.isEmpty()) return emptyList()
        if (!verified) {
            Log.i(LOG_TAG, "Profile is not verified, ignoring ${profileTags.size} vendor tags")
            return emptyList()
        }
        return profileTags.mapNotNull { (name, spec) ->
            when {
                isDisabled(name) -> null
                name !in availableRequestKeys -> {
                    Log.i(LOG_TAG, "Vendor tag $name not offered by this camera, skipped")
                    null
                }
                else -> parse(name, spec) ?: run {
                    disable(name, "invalid value \"$spec\"")
                    null
                }
            }
        }
    }

    companion object {
        private const val LOG_TAG = "VendorTagRegistry"

        /** Parses `"<type>:<value>"`; null when malformed. */
        fun parse(name: String, spec: String): Tag? {
            val type = spec.substringBefore(':', "").trim()
            val raw = spec.substringAfter(':', "").trim()
            if (name.isBlank() || raw.isEmpty()) return null
            return runCatching {
                when (type) {
                    "i32" -> Tag(name, Int::class.javaObjectType, raw.toInt())
                    "i64" -> Tag(name, Long::class.javaObjectType, raw.toLong())
                    "u8" -> Tag(name, Byte::class.javaObjectType, raw.toInt().also {
                        require(it in 0..255)
                    }.toByte())
                    "f32" -> Tag(name, Float::class.javaObjectType, raw.toFloat())
                    "f64" -> Tag(name, Double::class.javaObjectType, raw.toDouble())
                    "bool" -> Tag(name, Boolean::class.javaObjectType, raw.toBooleanStrict())
                    "i32[]" -> Tag(
                        name, IntArray::class.java,
                        raw.split(',').map { it.trim().toInt() }.toIntArray(),
                    )
                    else -> null
                }
            }.getOrNull()
        }

        /** The request key for [tag]; null below API 29, where keys cannot be built by name. */
        @Suppress("UNCHECKED_CAST")
        fun requestKey(tag: Tag): CaptureRequest.Key<Any>? = if (Build.VERSION.SDK_INT >= 29) {
            CaptureRequest.Key(tag.name, tag.type as Class<Any>)
        } else null
    }
}
