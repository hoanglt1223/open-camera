/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LensCatalogTest {
    private val profile = ProfileFactory.autoProfile(GauguinFixture.build, GauguinFixture.specs)
    private fun role(id: String) = profile.cameras.first { it.id == id }.role

    @Test
    fun gauguinZoomStops() {
        val stops = profile.zoomStops.map { Triple(it.ratio, it.cameraId, it.mode) }
        assertEquals(
            listOf(
                Triple(0.6f, "3", StopMode.PHYSICAL),
                Triple(1f, "0", StopMode.PHYSICAL),
                Triple(2f, "0", StopMode.CROP),
            ),
            stops,
        )
        assertEquals(listOf("0.6", "1x", "2"), profile.zoomStops.map { it.label })
    }

    @Test
    fun gauguinRoles() {
        assertEquals(LensRole.MAIN, role("0"))
        assertEquals(LensRole.FRONT, role("1"))
        assertEquals(LensRole.MACRO, role("2"))
        assertEquals(LensRole.ULTRA, role("3"))
        assertEquals(LensRole.DEPTH, role("4"))
        assertEquals(LensRole.LOGICAL, role("5"))
        assertEquals("3", profile.cameras.first { it.id == "6" }.duplicateOf)
        assertEquals("1", profile.cameras.first { it.id == "7" }.duplicateOf)
        assertNull(profile.cameras.first { it.id == "4" }.duplicateOf)
        assertTrue(Quirk.ULTRAWIDE_DUP_ID in profile.quirks)
    }

    @Test
    fun equivalentFocal() {
        assertEquals(25.4f, profile.cameras.first { it.id == "0" }.equivFocalMm, 0.1f)
        assertEquals(15.6f, profile.cameras.first { it.id == "3" }.equivFocalMm, 0.1f)
    }

    @Test
    fun jsonRoundTripKeepsLensPlan() {
        val parsed = ProfileJson.fromJson(JSONObject(ProfileJson.toJson(profile).toString()))
        assertEquals(profile.cameras.map { it.id to it.role }, parsed.cameras.map { it.id to it.role })
        assertEquals(profile.quirks, parsed.quirks)
        assertEquals(LensPlan.from(profile).stops, LensPlan.from(parsed).stops)
    }

    @Test
    fun singleCameraDeviceStillHasOneXAndCrop() {
        val spec = GauguinFixture.specs.first { it.id == "0" }
        val p = ProfileFactory.autoProfile(GauguinFixture.build, listOf(spec))
        assertEquals(listOf("1x", "2"), p.zoomStops.map { it.label })
    }

    @Test
    fun telephotoSuppressesCropStop() {
        val tele = CameraSpec("9", Facing.BACK, listOf(13f), 7.3984f, 5.5552f, 4000, 3000)
        val p = ProfileFactory.autoProfile(GauguinFixture.build, GauguinFixture.specs + tele)
        assertEquals(listOf("0.6", "1x", "2.4"), p.zoomStops.map { it.label })
    }

    @Test
    fun resolverPrefersVerifiedThenOverride() {
        val verified = profile.copy(source = ProfileSource.VERIFIED, knownBugs = listOf("v"))
        val community = profile.copy(source = ProfileSource.COMMUNITY)
        val build = GauguinFixture.build
        assertEquals(verified, ProfileResolver.resolve(build, profile, listOf(community, verified)))
        val override = profile.copy(knownBugs = listOf("o"))
        assertEquals(override, ProfileResolver.resolve(build, profile, listOf(verified), override))
        val other = verified.copy(match = verified.match.copy(device = listOf("x"), model = listOf("y")))
        assertEquals(profile, ProfileResolver.resolve(build, profile, listOf(other)))
    }

    @Test
    fun lensPlanPicksLargestStopBelowZoom() {
        val plan = LensPlan.from(profile)
        assertEquals("0.6", plan.stopFor(0.8f)?.label)
        assertEquals("1x", plan.stopFor(1.9f)?.label)
        assertEquals("2", plan.stopFor(5f)?.label)
    }

    @Test
    fun logicalZoomOnlyForPhysicalsInRange() {
        val wide = LensChip("0.6", 0.6f, "3", 1f)
        assertEquals(0.6f, LensPlan.logicalZoom(wide, setOf("0", "3"), 0.6f, 10f))
        // Not a physical camera of the open logical camera (gauguin: logical 5 = main + depth).
        assertNull(LensPlan.logicalZoom(wide, setOf("0", "4"), 1f, 10f))
        // The logical camera cannot zoom out that far.
        assertNull(LensPlan.logicalZoom(wide, setOf("0", "3"), 1f, 10f))
        assertNull(LensPlan.logicalZoom(wide, emptySet(), 0.5f, 10f))
    }
}