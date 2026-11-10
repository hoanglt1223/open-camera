/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.repositories

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import dev.opencamera.profiles.BuildInfo
import dev.opencamera.profiles.CameraSpec
import dev.opencamera.profiles.Capability
import dev.opencamera.profiles.DeviceProfile
import dev.opencamera.profiles.Facing
import dev.opencamera.profiles.ProfileFactory
import dev.opencamera.profiles.ProfileJson
import dev.opencamera.profiles.ProfileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Device profile: generated from Camera2 on first launch, cached per build fingerprint,
 * merged with the best bundled profile, or replaced by the one pinned in Settings or a
 * user override file.
 */
class DeviceProfileRepository(private val context: Context) {
    private val mutex = Mutex()
    private var auto: DeviceProfile? = null
    private var bundle: List<DeviceProfile>? = null

    private val _resolved = MutableStateFlow<DeviceProfile?>(null)

    /** The profile in use; null until the first resolution finishes. */
    val resolved = _resolved.asStateFlow()

    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(context) }

    /** Profile id pinned in Settings, [ProfileResolver.AUTO_ID], [PINNED_OFF] or null (best match). */
    val pinnedId: String? get() = preferences.getString(PINNED_PROFILE_KEY, null)?.ifEmpty { null }

    private val buildInfo = BuildInfo(
        brand = Build.BRAND,
        device = Build.DEVICE,
        model = Build.MODEL,
        fingerprint = Build.FINGERPRINT,
        sdkInt = Build.VERSION.SDK_INT,
    )

    private val cacheFile
        get() = File(
            context.noBackupFilesDir,
            "device-profile-${Integer.toHexString(Build.FINGERPRINT.hashCode())}.json"
        )

    /** A user-supplied profile placed here wins over every other source. */
    val overrideFile get() = File(context.filesDir, OVERRIDE_FILE_NAME)

    suspend fun profile(): DeviceProfile = _resolved.value ?: refresh()

    /** Resolves again, e.g. after the pinned profile changed. */
    suspend fun refresh(): DeviceProfile = withContext(Dispatchers.Default) {
        mutex.withLock {
            resolve().also {
                _resolved.value = it
                Log.i(LOG_TAG, "Profile ${it.id} (${it.source.key}), stops ${
                    it.zoomStops.joinToString { s -> "${s.label}->${s.cameraId}/${s.mode.key}" }
                }")
            }
        }
    }

    /** Bundled profiles, for the Settings picker. */
    suspend fun bundledProfiles(): List<DeviceProfile> = withContext(Dispatchers.Default) {
        mutex.withLock { bundled() }
    }

    suspend fun exportJson(): String = ProfileJson.toJson(profile()).toString(2)

    private fun resolve(): DeviceProfile {
        val auto = auto ?: (read(cacheFile) ?: ProfileFactory.autoProfile(buildInfo, scan()).also {
            runCatching { cacheFile.writeText(ProfileJson.toJson(it).toString()) }
                .onFailure { e -> Log.w(LOG_TAG, "Cannot cache profile", e) }
        }).also { auto = it }
        if (pinnedId == PINNED_OFF) return auto.copy(zoomStops = emptyList())
        return ProfileResolver.resolve(buildInfo, auto, bundled(), read(overrideFile), pinnedId)
    }

    private fun read(file: File): DeviceProfile? = if (!file.exists()) null else runCatching {
        ProfileJson.fromJson(JSONObject(file.readText()))
    }.onFailure { Log.w(LOG_TAG, "Ignoring invalid profile ${file.name}", it) }.getOrNull()

    private fun bundled(): List<DeviceProfile> = bundle ?: runCatching {
        context.assets.open(BUNDLE_ASSET).bufferedReader().use { reader ->
            val array = JSONArray(reader.readText())
            List(array.length()) { i ->
                runCatching { ProfileJson.fromJson(array.getJSONObject(i)) }
                    .onFailure { Log.w(LOG_TAG, "Skipping bundled profile $i", it) }
                    .getOrNull()
            }.filterNotNull()
        }
    }.onFailure { Log.w(LOG_TAG, "No bundled profiles", it) }
        .getOrDefault(emptyList())
        .also { bundle = it }

    private fun scan(): List<CameraSpec> {
        val manager = context.getSystemService(CameraManager::class.java)
        val listed = manager.cameraIdList.toSet()
        val probed = (0 until HIDDEN_ID_PROBE_LIMIT).map(Int::toString).filterNot(listed::contains)
        return (listed.map { it to false } + probed.map { it to true }).mapNotNull { (id, hidden) ->
            try {
                spec(id, manager.getCameraCharacteristics(id), hidden)
            } catch (e: CameraAccessException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    private fun spec(id: String, c: CameraCharacteristics, hidden: Boolean): CameraSpec? {
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> Facing.FRONT
            CameraMetadata.LENS_FACING_BACK -> Facing.BACK
            else -> Facing.EXTERNAL
        }
        val rawCapabilities: IntArray =
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val capabilities = mutableSetOf<Capability>()
        for (value in rawCapabilities) {
            capabilityOf(value)?.let(capabilities::add)
        }
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            c.physicalCameraIds.toList()
        } else {
            emptyList()
        }
        return CameraSpec(
            id = id,
            facing = facing,
            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.toList().orEmpty(),
            sensorWidthMm = sensor.width,
            sensorHeightMm = sensor.height,
            pixelWidth = pixels.width,
            pixelHeight = pixels.height,
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            capabilities = capabilities,
            physicalIds = physicalIds,
            hidden = hidden,
        )
    }

    private fun capabilityOf(value: Int): Capability? = when (value) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE ->
            Capability.BACKWARD_COMPATIBLE
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA ->
            Capability.LOGICAL_MULTI_CAMERA
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> Capability.MONOCHROME
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> Capability.DEPTH_OUTPUT
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> Capability.RAW
        else -> null
    }

    companion object {
        private val LOG_TAG = DeviceProfileRepository::class.simpleName!!
        private const val HIDDEN_ID_PROBE_LIMIT = 16
        private const val BUNDLE_ASSET = "profiles.bundle.json"
        const val OVERRIDE_FILE_NAME = "device-profile-override.json"
        const val PINNED_PROFILE_KEY = "device_profile"

        /** Pinned value that turns profiles off: no zoom stops, so the plain camera list is shown. */
        const val PINNED_OFF = "off"
    }
}