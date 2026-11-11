/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.vendorbridge

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log

/** What one camera exposes: OEM extensions, Ultra HDR output and vendor tags. */
data class CameraProbe(
    val cameraId: String,
    val facing: Int?,
    /** CameraX extensions found available; null when CameraX was not asked. */
    val cameraX: Set<OemFeature>?,
    val camera2: List<Camera2Extension>,
    val ultraHdr: Boolean,
    val vendorCharacteristicKeys: List<String>,
    val vendorRequestKeys: List<String>,
    val vendorResultKeys: List<String>,
    val error: String? = null,
)

data class Camera2Extension(
    val id: Int,
    val name: String,
    val jpegSizes: Int,
    val postview: Boolean?,
    val strength: Boolean?,
    val captureProgress: Boolean?,
)

/** Read-only discovery; every call is guarded, a failing camera reports its error instead. */
object ExtensionsProbe {
    private const val LOG_TAG = "VendorBridge"
    private const val JPEG_R = 0x1005 // ImageFormat.JPEG_R, API 34

    fun isVendorKey(name: String) = !name.startsWith("android.")

    fun probe(manager: CameraManager, cameraId: String, cameraX: Set<OemFeature>?): CameraProbe =
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            CameraProbe(
                cameraId = cameraId,
                facing = chars.get(CameraCharacteristics.LENS_FACING),
                cameraX = cameraX,
                camera2 = camera2Extensions(manager, cameraId),
                ultraHdr = Build.VERSION.SDK_INT >= 34 && runCatching {
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.outputFormats?.contains(JPEG_R) == true
                }.getOrDefault(false),
                vendorCharacteristicKeys = chars.keys.map { it.name }.filter(::isVendorKey).sorted(),
                vendorRequestKeys = chars.availableCaptureRequestKeys.map { it.name }
                    .filter(::isVendorKey).sorted(),
                vendorResultKeys = chars.availableCaptureResultKeys.map { it.name }
                    .filter(::isVendorKey).sorted(),
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Probe of camera $cameraId failed", e)
            CameraProbe(cameraId, null, cameraX, emptyList(), false, emptyList(), emptyList(),
                emptyList(), e.toString())
        }

    /** Request key names of [cameraId], vendor ones included; empty on failure. */
    fun requestKeyNames(manager: CameraManager, cameraId: String): Set<String> = runCatching {
        manager.getCameraCharacteristics(cameraId).availableCaptureRequestKeys
            .map { it.name }.toSet()
    }.getOrDefault(emptySet())

    private fun camera2Extensions(manager: CameraManager, cameraId: String): List<Camera2Extension> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        val chars = runCatching { manager.getCameraExtensionCharacteristics(cameraId) }
            .onFailure { Log.w(LOG_TAG, "No extension characteristics for $cameraId", it) }
            .getOrNull() ?: return emptyList()
        return runCatching { chars.supportedExtensions }.getOrDefault(emptyList()).map { ext ->
            Camera2Extension(
                id = ext,
                name = OemFeature.fromCamera2Extension(ext)?.key ?: "extension_$ext",
                jpegSizes = runCatching {
                    chars.getExtensionSupportedSizes(ext, ImageFormat.JPEG).size
                }.getOrDefault(0),
                postview = if (Build.VERSION.SDK_INT >= 34) {
                    runCatching { chars.isPostviewAvailable(ext) }.getOrNull()
                } else null,
                strength = if (Build.VERSION.SDK_INT >= 34) {
                    runCatching {
                        chars.getAvailableCaptureRequestKeys(ext)
                            .contains(CaptureRequest.EXTENSION_STRENGTH)
                    }.getOrNull()
                } else null,
                captureProgress = if (Build.VERSION.SDK_INT >= 34) {
                    runCatching { chars.isCaptureProcessProgressAvailable(ext) }.getOrNull()
                } else null,
            )
        }
    }

    /** Human readable dump for logcat and bug reports. */
    fun format(probes: List<CameraProbe>): String = buildString {
        appendLine("Vendor bridge probe: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), " +
                "API ${Build.VERSION.SDK_INT}")
        for (p in probes) {
            appendLine("camera ${p.cameraId} facing=${p.facing}" + (p.error?.let { " error=$it" } ?: ""))
            appendLine("  camerax: ${p.cameraX?.joinToString { it.key }?.ifEmpty { "none" } ?: "not probed"}")
            appendLine("  camera2: ${p.camera2.joinToString {
                "${it.name}(jpegSizes=${it.jpegSizes}, postview=${it.postview}, " +
                        "strength=${it.strength}, progress=${it.captureProgress})"
            }.ifEmpty { "none" }}")
            appendLine("  ultraHdr(JPEG_R): ${p.ultraHdr}")
            appendLine("  vendor characteristics (${p.vendorCharacteristicKeys.size}): " +
                    p.vendorCharacteristicKeys.joinToString())
            appendLine("  vendor request keys (${p.vendorRequestKeys.size}): " +
                    p.vendorRequestKeys.joinToString())
            appendLine("  vendor result keys (${p.vendorResultKeys.size}): " +
                    p.vendorResultKeys.joinToString())
        }
    }
}
