/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.vendorbridge

import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Uses what the phone maker exposes through public APIs (CameraX / Camera2 extensions, vendor
 * request tags) without bundling any vendor code. Everything is optional: the whole bridge is
 * off with [KEY_ENABLED] = false, an OEM mode that fails falls back to the open pipeline for
 * the session, and a vendor tag that fails is dropped.
 */
class VendorBridge(private val prefs: SharedPreferences) {
    val tags = VendorTagRegistry()

    private val oemFailed = mutableSetOf<OemFeature>()

    /** Tags whose write was still pending when the process died last time. */
    private val blockedTags: Set<String>

    init {
        val pending = prefs.getStringSet(KEY_TAGS_PENDING, null).orEmpty()
        val blocked = prefs.getStringSet(KEY_TAGS_BLOCKED, null).orEmpty()
        blockedTags = if (pending.isNotEmpty()) {
            Log.e(LOG_TAG, "App stopped while writing vendor tags $pending, blocking them")
            (blocked + pending).also {
                prefs.edit().putStringSet(KEY_TAGS_BLOCKED, it).remove(KEY_TAGS_PENDING).commit()
            }
        } else blocked
        blockedTags.forEach { tags.disable(it, "blocked after an earlier failure") }
    }

    val enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)

    /** Auto HDR follows the HDR choice. */
    fun sourceChoice(feature: OemFeature): ProcessingSource =
        ProcessingSource.fromKey(prefs.getString(sourceKey(feature), null)) ?: ProcessingSource.PHONE

    @Synchronized
    fun resolve(feature: OemFeature, oemAvailable: Boolean): ProcessingSource =
        SourcePolicy.resolve(sourceChoice(feature), enabled, oemAvailable, feature in oemFailed)

    @Synchronized
    fun markOemFailed(feature: OemFeature) {
        if (oemFailed.add(feature)) Log.w(LOG_TAG, "OEM ${feature.key} failed, open pipeline for this session")
    }

    /** Vendor tags for a camera, or none when the bridge is off. */
    fun selectTags(
        profileTags: Map<String, String>,
        verified: Boolean,
        availableRequestKeys: Set<String>,
    ): List<VendorTagRegistry.Tag> = if (enabled) {
        tags.select(profileTags, verified, availableRequestKeys)
    } else emptyList()

    /** Marks [names] as being written; a crash before [endTagWrite] blocks them next launch. */
    fun beginTagWrite(names: Collection<String>) {
        if (names.isNotEmpty()) prefs.edit().putStringSet(KEY_TAGS_PENDING, names.toSet()).commit()
    }

    fun endTagWrite() {
        prefs.edit().remove(KEY_TAGS_PENDING).apply()
    }

    private var dumped = false

    /** Probes every camera once per process and logs the report. */
    @Synchronized
    fun dumpOnce(manager: CameraManager, cameraX: Map<String, Set<OemFeature>>) {
        if (dumped) return
        dumped = true
        dump(manager, cameraX)
    }

    /** Probes every camera and logs the report; [cameraX] maps camera id to available features. */
    fun dump(manager: CameraManager, cameraX: Map<String, Set<OemFeature>>): String {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val report = ExtensionsProbe.format(
            (ids + cameraX.keys).distinct().map { ExtensionsProbe.probe(manager, it, cameraX[it]) }
        )
        report.lines().forEach { Log.i(LOG_TAG, it) }
        return report
    }

    companion object {
        private const val LOG_TAG = "VendorBridge"
        const val KEY_ENABLED = "vendor_bridge_enabled"
        private const val KEY_TAGS_PENDING = "vendor_tags_pending"
        private const val KEY_TAGS_BLOCKED = "vendor_tags_blocked"

        fun sourceKey(feature: OemFeature) =
            "processing_source_${(if (feature == OemFeature.AUTO) OemFeature.HDR else feature).key}"
    }
}
