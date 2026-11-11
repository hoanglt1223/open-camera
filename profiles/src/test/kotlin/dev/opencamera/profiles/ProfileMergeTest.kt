/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

import dev.opencamera.profiles.Capability.BACKWARD_COMPATIBLE
import dev.opencamera.profiles.Capability.LOGICAL_MULTI_CAMERA
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileMergeTest {
    private val caps = setOf(BACKWARD_COMPATIBLE)

    /** gauguin as scanned by the app: only ids 0 and 1 are listed, 2..7 come from probing. */
    private val gauguinScan = GauguinFixture.specs.map { it.copy(hidden = it.id.toInt() > 1) }
    private val gauguinAuto = ProfileFactory.autoProfile(GauguinFixture.build, gauguinScan)

    private fun catalog(id: String, brand: String, models: List<String>, vararg lenses: CatalogLens) =
        DeviceProfile(
            id = id,
            match = ProfileMatch(brand = brand, model = models),
            lenses = lenses.toList(),
            source = ProfileSource.COMMUNITY,
        )

    private fun labels(p: DeviceProfile) = p.zoomStops.map { it.label }

    @Test
    fun gauguinHiddenStopsNeedAnOpenableId() {
        assertTrue(Quirk.HIDDEN_PHYSICAL_IDS in gauguinAuto.quirks)
        val plan = LensPlan.from(gauguinAuto)
        assertEquals(
            listOf(LensChip("0.6", 0.6f, "3", 1f), LensChip("1x", 1f, "0", 1f), LensChip("2", 2f, "0", 2f)),
            plan.chips(setOf("0", "1", "3", "6")),
        )
        // Id 3 not openable but its duplicate 6 is.
        assertEquals(listOf("6", "0", "0"), plan.chips(setOf("0", "6")).map { it.cameraId })
        // Neither: the 0.6 chip is dropped rather than shown dead.
        assertEquals(listOf("1x", "2"), plan.chips(setOf("0", "1")).map { it.label })
        assertEquals(emptyList(), plan.chips(setOf("1")))
    }

    @Test
    fun gauguinVerifiedProfileWinsAndIsUsedAsIs() {
        val verified = ProfileJson.fromJson(JSONObject(ProfileJson.toJson(gauguinAuto).toString()))
            .copy(source = ProfileSource.VERIFIED, match = ProfileMatch("xiaomi", device = listOf("gauguin")))
        val community = catalog(
            "redmi/note9pro5g", "redmi", listOf("M2007J17C"),
            CatalogLens(LensRole.MAIN, ratio = 1f), CatalogLens(LensRole.TELE, ratio = 3f, official = true),
        )
        val build = GauguinFixture.build.copy(model = "M2007J17C")
        val resolved = ProfileResolver.resolve(build, gauguinAuto, listOf(community, verified))
        assertSame(verified, resolved)
        assertEquals(listOf("0.6", "1x", "2"), labels(resolved))
    }

    @Test
    fun threeLensPhoneTakesPublishedLabels() {
        // Synthetic iQOO 13-like scan: 50 MP main, ultra and a tele that measures 1.9x.
        val scan = listOf(
            CameraSpec("0", Facing.BACK, listOf(6.7f), 8.19f, 6.14f, 8160, 6144, 10f, caps),
            CameraSpec("1", Facing.FRONT, listOf(3.3f), 4.7f, 3.5f, 4000, 3000, 0f, caps),
            CameraSpec("2", Facing.BACK, listOf(2.2f), 4.4f, 3.3f, 8160, 6144, 10f, caps),
            CameraSpec("3", Facing.BACK, listOf(7.8f), 5.0f, 3.75f, 8160, 6144, 5f, caps),
            CameraSpec("4", Facing.BACK, listOf(1.8f), 2.8f, 2.1f, 1600, 1200, 20f, caps),
        )
        val build = BuildInfo("iQOO", "PD2408", "V2408A", "iQOO/PD2408/PD2408:15/test", 35)
        val auto = ProfileFactory.autoProfile(build, scan)
        assertEquals(listOf("0.6", "1x", "1.9"), labels(auto))

        val iqoo13 = catalog(
            "vivo/iqoo13", "vivo", listOf("V2408A", "V2408", "I2401"),
            CatalogLens(LensRole.ULTRA), CatalogLens(LensRole.MAIN, ratio = 1f),
            CatalogLens(LensRole.TELE, ratio = 2f, official = true),
        )
        val other = catalog("vivo/x200", "vivo", listOf("V2405A"), CatalogLens(LensRole.MAIN, ratio = 1f))
        val p = ProfileResolver.resolve(build, auto, listOf(other, iqoo13))
        assertEquals("vivo/iqoo13", p.id)
        assertEquals(ProfileSource.COMMUNITY, p.source)
        assertEquals(listOf("0.6", "1x", "2"), labels(p))
        assertEquals(listOf("2", "0", "3"), p.zoomStops.map { it.cameraId })
        assertTrue(p.zoomStops.none { it.mode == StopMode.CROP })
        assertEquals(listOf(1f, 1f, 1f), LensPlan.from(p).chips(setOf("0", "1", "2", "3")).map { it.zoomRatio })
    }

    @Test
    fun publishedTeleThatCannotBeFoundBecomesCrop() {
        val build = GauguinFixture.build
        val withTele = catalog(
            "xiaomi/gauguin", "xiaomi", listOf("M2007J17G"),
            CatalogLens(LensRole.ULTRA), CatalogLens(LensRole.MAIN, ratio = 1f),
            CatalogLens(LensRole.TELE, ratio = 3f, official = true),
        )
        val p = ProfileResolver.resolve(build, gauguinAuto, listOf(withTele))
        assertEquals(listOf("0.6", "1x", "3"), labels(p))
        assertEquals(StopMode.CROP, p.zoomStops.last().mode)
    }

    @Test
    fun singleLensPhoneGetsOneXAndCrop() {
        // OPPO A6-like: one 50 MP main plus a 2 MP macro.
        val scan = listOf(
            CameraSpec("0", Facing.BACK, listOf(4.7f), 6.4f, 4.8f, 8160, 6144, 10f, caps),
            CameraSpec("1", Facing.FRONT, listOf(2.9f), 3.6f, 2.7f, 3264, 2448, 0f, caps),
            CameraSpec("2", Facing.BACK, listOf(1.9f), 1.75f, 1.31f, 1600, 1200, 25f, caps),
        )
        val build = BuildInfo("OPPO", "OP5DA1", "PLS120", "OPPO/PLS120/OP5DA1:15/test", 35)
        val a6 = catalog("oppo/a6", "oppo", listOf("PLS120"), CatalogLens(LensRole.MAIN, 27f, 1f))
        val p = ProfileResolver.resolve(build, ProfileFactory.autoProfile(build, scan), listOf(a6))
        assertEquals("oppo/a6", p.id)
        assertEquals(LensRole.MACRO, p.cameras.first { it.id == "2" }.role)
        assertEquals(listOf("1x", "2"), labels(p))
        assertEquals(
            listOf(LensChip("1x", 1f, "0", 1f), LensChip("2", 2f, "0", 2f)),
            LensPlan.from(p).chips(setOf("0", "1")),
        )
    }

    @Test
    fun catalogDropsStopsForUnpublishedRoles() {
        // The scan calls a 5 MP sensor "ultra"; the spec page lists only main + macro.
        val scan = listOf(
            CameraSpec("0", Facing.BACK, listOf(4.7f), 6.4f, 4.8f, 8160, 6144, 10f, caps),
            CameraSpec("2", Facing.BACK, listOf(1.6f), 2.9f, 2.2f, 2592, 1944, 10f, caps),
        )
        val build = BuildInfo("vivo", "PD2229", "V2229A", "vivo/x:13/test", 33)
        val auto = ProfileFactory.autoProfile(build, scan)
        assertEquals(listOf("0.7", "1x", "2"), labels(auto))
        val y77e = catalog(
            "vivo/y77e", "vivo", listOf("V2229A"),
            CatalogLens(LensRole.MAIN, ratio = 1f), CatalogLens(LensRole.MACRO),
        )
        assertEquals(listOf("1x", "2"), labels(ProfileResolver.resolve(build, auto, listOf(y77e))))
    }

    @Test
    fun mainLabelledAboveOneScalesLabels() {
        // vivo X300 Ultra-like: 35 mm main labelled 1.5x, 85 mm tele labelled 3.7x.
        val scan = listOf(
            CameraSpec("0", Facing.BACK, listOf(9.0f), 8.8f, 6.6f, 8160, 6144, 10f, caps),
            CameraSpec("2", Facing.BACK, listOf(3.6f), 8.8f, 6.6f, 8160, 6144, 10f, caps),
            CameraSpec("3", Facing.BACK, listOf(21.9f), 8.8f, 6.6f, 8160, 6144, 3f, caps),
        )
        val build = BuildInfo("vivo", "PD2547", "V2547A", "vivo/x:16/test", 36)
        val x300 = catalog(
            "vivo/x300ultra", "vivo", listOf("V2547A"),
            CatalogLens(LensRole.ULTRA, 14f, 0.6f), CatalogLens(LensRole.MAIN, 35f, 1.5f, official = true),
            CatalogLens(LensRole.TELE, 85f, 3.7f, official = true),
        )
        val p = ProfileResolver.resolve(build, ProfileFactory.autoProfile(build, scan), listOf(x300))
        assertEquals(listOf("0.6", "1.5", "3.7"), labels(p))
        assertEquals(1f, p.zoomStops[1].ratio)
    }

    @Test
    fun listedLogicalCameraWithHiddenMembersZoomsLogically() {
        val scan = listOf(
            CameraSpec(
                "0", Facing.BACK, listOf(5.4f), 7.4f, 5.6f, 4624, 3472, 10f,
                caps + LOGICAL_MULTI_CAMERA, physicalIds = listOf("2", "3", "4"),
            ),
            CameraSpec("1", Facing.FRONT, listOf(3.3f), 4.7f, 3.5f, 4000, 3000, 0f, caps),
            CameraSpec("2", Facing.BACK, listOf(5.4f), 7.4f, 5.6f, 4624, 3472, 10f, caps, hidden = true),
            CameraSpec("3", Facing.BACK, listOf(1.65f), 3.66f, 2.74f, 3264, 2448, 0f, caps, hidden = true),
            CameraSpec("4", Facing.BACK, listOf(13f), 5.0f, 3.75f, 4000, 3000, 3f, caps, hidden = true),
        )
        val p = ProfileFactory.autoProfile(GauguinFixture.build, scan)
        assertTrue(Quirk.USE_LOGICAL_ZOOM in p.quirks)
        assertTrue(p.zoomStops.all { it.cameraId == "0" && it.mode == StopMode.LOGICAL })
        val chips = LensPlan.from(p).chips(setOf("0", "1"))
        assertEquals(listOf(0.6f, 1f, 3.6f), chips.map { it.zoomRatio })
        assertEquals("1x", LensPlan.activeChip(chips, "0", 1.4f)?.label)
        assertEquals("0.6", LensPlan.activeChip(chips, "0", 0.6f)?.label)
    }

    @Test
    fun pinnedProfileAndBrandFamilies() {
        val build = GauguinFixture.build.copy(brand = "Redmi")
        val db = ProfileJson.fromJson(JSONObject(ProfileJson.toJson(gauguinAuto).toString()))
            .copy(id = "xiaomi/gauguin", source = ProfileSource.VERIFIED)
        assertSame(db, ProfileResolver.resolve(build, gauguinAuto, listOf(db)))
        assertSame(gauguinAuto, ProfileResolver.resolve(build, gauguinAuto, listOf(db), pinnedId = ProfileResolver.AUTO_ID))
        val other = catalog("oppo/a6", "oppo", listOf("PLS120"), CatalogLens(LensRole.MAIN, ratio = 1f))
        assertEquals("oppo/a6", ProfileResolver.resolve(build, gauguinAuto, listOf(db, other), pinnedId = "oppo/a6").id)
    }

    @Test
    fun catalogJsonRoundTrip() {
        val json = """
            {"schemaVersion":1,"id":"vivo/iqoo13","name":"iQOO 13","match":{"brand":"vivo","model":["V2408A"]},
             "lenses":[{"role":"ultra","runtimeFields":["equivFocalMm","ratio"]},
                       {"role":"main","ratio":1,"ratioOrigin":"derived","runtimeFields":["equivFocalMm"]},
                       {"role":"tele","ratio":2.0,"ratioOrigin":"official_optical_zoom"}],
             "quirks":["SOME_FUTURE_QUIRK"],"source":"community"}
        """.trimIndent()
        val p = ProfileJson.fromJson(JSONObject(json))
        assertEquals(false, p.isMeasured)
        assertEquals(emptySet(), p.quirks)
        assertEquals(CatalogLens(LensRole.TELE, null, 2f, official = true), p.lenses.last())
        assertEquals(p, ProfileJson.fromJson(JSONObject(ProfileJson.toJson(p).toString())))
    }
}
