/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Parses every profile of the open-camera-profiles checkout (skipped when none is configured). */
class BundledProfilesTest {
    private val dataDir = System.getProperty("openCamera.profiles.dir")?.let { File(it, "data") }

    private fun profiles(): List<DeviceProfile> {
        assumeTrue("openCamera.profiles.dir not set", dataDir?.isDirectory == true)
        return dataDir!!.walkTopDown().filter { it.extension == "json" }
            .map { ProfileJson.fromJson(JSONObject(it.readText())) }
            .toList()
    }

    @Test
    fun everyProfileParsesAndGauguinResolvesToVerified() {
        val all = profiles()
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val scanned = ProfileFactory.autoProfile(
            GauguinFixture.build,
            GauguinFixture.specs.map { it.copy(hidden = it.id.toInt() > 1) },
        )
        val p = ProfileResolver.resolve(GauguinFixture.build, scanned, all)
        assertEquals("xiaomi/gauguin", p.id)
        assertEquals(ProfileSource.VERIFIED, p.source)
        assertEquals(listOf("0.6", "1x", "2"), p.zoomStops.map { it.label })
        assertEquals(listOf("3", "0", "0"), p.zoomStops.map { it.cameraId })
    }

    @Test
    fun iqoo13ResolvesFromModelCode() {
        val build = BuildInfo("iQOO", "PD2408", "V2408A", "iQOO/PD2408/PD2408:15/test", 35)
        assertEquals("vivo/iqoo13", ProfileResolver.bestMatch(build, profiles())?.id)
    }
}
