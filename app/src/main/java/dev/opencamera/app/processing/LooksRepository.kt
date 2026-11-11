/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dev.opencamera.pipeline.looks.CubeParser
import dev.opencamera.pipeline.looks.LookDescriptor
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.Lut3d
import dev.opencamera.pipeline.looks.UserLookStore
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Built-in looks from `assets/looks` plus user looks (colour match, imported `.cube`) from
 * `files/looks/user`. LUTs are parsed on first use and cached.
 */
class LooksRepository private constructor(context: Context) {
    private val assets = context.applicationContext.assets
    private val lutCache = File(context.applicationContext.cacheDir, "luts")
    private val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
    private val luts = HashMap<String, Lut3d>()
    val userLooks = UserLookStore(File(context.applicationContext.filesDir, "looks/user"))

    private val builtIn: List<LookDescriptor> by lazy {
        val index = JSONObject(read("$ROOT/index.json")).getJSONArray("looks")
        List(index.length()) { LookDescriptor.parse(read("$ROOT/${index.getString(it)}/look.json")) }
    }

    @Volatile
    private var user: List<LookDescriptor>? = null

    val descriptors: List<LookDescriptor>
        get() = builtIn + (user ?: userLooks.list().also { user = it })

    /** Re-reads user looks after one was added or removed. */
    @Synchronized
    fun reloadUserLooks() {
        user?.forEach { luts.remove(it.id) }
        user = userLooks.list()
    }

    fun descriptor(id: String?) = descriptors.firstOrNull { it.id == id }

    /** Render parameters for [id], or null for "no look". Blocking; call off the main thread. */
    fun params(id: String?, intensity: Float): LookParams? {
        val d = descriptor(id) ?: return null
        return LookParams.of(d, lut(d), intensity)
    }

    @Synchronized
    private fun lut(d: LookDescriptor): Lut3d = luts.getOrPut(d.id) {
        if (d.id.startsWith(UserLookStore.PREFIX)) userLooks.lut(d) else builtInLut(d)
    }

    /** Built-in `.cube` text takes seconds to parse on a phone; a binary copy loads in ms. */
    private fun builtInLut(d: LookDescriptor): Lut3d {
        val cached = File(lutCache, "${d.id}-$appVersion.bin")
        runCatching {
            DataInputStream(cached.inputStream().buffered()).use { input ->
                val size = input.readInt()
                val data = FloatArray(size * size * size * 3)
                val bytes = ByteArray(data.size * 4)
                input.readFully(bytes)
                ByteBuffer.wrap(bytes).asFloatBuffer().get(data)
                return Lut3d(size, data)
            }
        }
        val lut = CubeParser.parse(read("$ROOT/${d.id}/${d.lutFile}"))
        runCatching {
            lutCache.mkdirs()
            val tmp = File(lutCache, "${d.id}.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(lut.size)
                val bytes = ByteBuffer.allocate(lut.data.size * 4)
                bytes.asFloatBuffer().put(lut.data)
                out.write(bytes.array())
            }
            tmp.renameTo(cached)
        }
        return lut
    }

    private fun read(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val ROOT = "looks"

        @Volatile
        private var instance: LooksRepository? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: LooksRepository(context).also { instance = it }
        }
    }
}

/** Look and frame settings, stored in the default preferences. */
class LookPreferences(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    var lookId: String
        get() = prefs.getString(KEY_LOOK, NONE) ?: NONE
        set(value) = prefs.edit().putString(KEY_LOOK, value).apply()

    val intensity: Float get() = prefs.getInt(KEY_INTENSITY, 100).coerceIn(0, 100) / 100f

    val watermark: WatermarkTemplate get() = WatermarkTemplate.fromKey(prefs.getString(KEY_WATERMARK, null))

    companion object {
        const val NONE = "none"
        const val KEY_LOOK = "look_id"
        const val KEY_INTENSITY = "look_intensity"
        const val KEY_WATERMARK = "watermark_template"
    }
}
